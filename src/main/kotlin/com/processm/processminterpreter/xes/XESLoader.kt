package com.processm.processminterpreter.xes

import com.fasterxml.jackson.databind.ObjectMapper
import com.processm.processminterpreter.service.LogService
import org.neo4j.driver.Driver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.InputStream
import java.time.LocalDateTime

/**
 * Service for loading XES files into Neo4j database
 *
 * Handles parsing XES files and storing them with proper relationships
 */
@Service
class XESLoader(
    private val xesParser: XESParser,
    private val logService: LogService,
    private val neo4jDriver: Driver,
) {

    private val logger = LoggerFactory.getLogger(XESLoader::class.java)
    private val objectMapper = ObjectMapper()
    private val BATCH_SIZE = 25 // Process 25 traces at a time - balance between memory and performance

    init {
        createIndexes()
    }

    /**
     * Ensures that necessary indexes exist in Neo4j for efficient queries.
     */
    private fun createIndexes() {
        logger.info("Ensuring Neo4j indexes are created...")
        try {
            neo4jDriver.session().use { session ->
                session.executeWrite { tx ->
                    tx.run("CREATE INDEX log_logId IF NOT EXISTS FOR (n:Log) ON (n.logId)").consume()
                    tx.run("CREATE INDEX trace_traceId IF NOT EXISTS FOR (n:Trace) ON (n.traceId)").consume()
                    tx.run("CREATE INDEX event_eventId IF NOT EXISTS FOR (n:Event) ON (n.eventId)").consume()
                }
            }
            logger.info("Neo4j indexes are in place.")
        } catch (e: Exception) {
            logger.error("Failed to create Neo4j indexes. Performance may be degraded.", e)
        }
    }

    /**
     * Sanitize attributes to be Neo4j-compatible.
     * Replaces colons in keys and converts complex values to JSON strings.
     */
    private fun sanitizeAttributes(attributes: Map<String, Any>): Map<String, Any> {
        // NOTE: Keep colons in attribute keys to match ProcessM format
        // Only replace dots as they conflict with Neo4j property path notation
        return attributes.mapKeys { it.key.replace(".", "_") }
            .mapValues { (_, value) ->
                when (value) {
                    is Map<*, *>, is Collection<*> -> objectMapper.writeValueAsString(value)
                    else -> value
                }
            }
    }

    /**
     * Load XES file into Neo4j database
     */
    fun loadXESFile(inputStream: InputStream, logId: String? = null): XESLoadResult {
        logger.info("Starting XES file loading for logId: $logId")

        return try {
            // Parse XES file
            val xesLog = xesParser.parseXES(inputStream, logId)

            // Save to Neo4j with relationships
            val savedLogId = saveXESLogToNeo4j(xesLog)

            XESLoadResult(
                success = true,
                logId = savedLogId,
                tracesCount = xesLog.traces.size,
                eventsCount = xesLog.traces.sumOf { it.events.size },
                message = "XES file loaded successfully",
            )
        } catch (e: Exception) {
            logger.error("Error loading XES file", e)
            XESLoadResult(
                success = false,
                error = e.cause?.message ?: e.message ?: "Unknown error occurred",
                message = "Failed to load XES file",
            )
        }
    }

    /**
     * Save parsed XES log to Neo4j using efficient batch operations in separate transactions.
     */
    private fun saveXESLogToNeo4j(xesLog: XESLog): String {
        val logId = xesLog.logNode.logId
        logger.info("Saving XES log to Neo4j: $logId. Total traces: ${xesLog.traces.size}")

        // 1. Create Log node first in its own transaction
        neo4jDriver.session().use { session ->
            session.executeWrite { tx ->
                val createLogQuery = """
                    CREATE (log:Log {
                        logId: ${'$'}logId,
                        name: ${'$'}name,
                        createdAt: ${'$'}createdAt,
                        updatedAt: ${'$'}updatedAt
                    })
                    SET log += ${'$'}attributes
                    SET log.classifiers = ${'$'}classifiers
                """
                val classifiersJson = if (xesLog.classifiers.isNotEmpty()) {
                    objectMapper.writeValueAsString(xesLog.classifiers)
                } else {
                    null
                }
                tx.run(
                    createLogQuery,
                    mapOf(
                        "logId" to logId,
                        "name" to xesLog.logNode.name,
                        "createdAt" to xesLog.logNode.createdAt,
                        "updatedAt" to xesLog.logNode.updatedAt,
                        "attributes" to sanitizeAttributes(xesLog.logNode.attributes),
                        "classifiers" to classifiersJson,
                    ),
                ).consume()
            }
        }

        // 2. Process traces in batches
        val totalTraces = xesLog.traces.size
        val totalBatches = (totalTraces + BATCH_SIZE - 1) / BATCH_SIZE
        xesLog.traces.chunked(BATCH_SIZE).forEachIndexed { index, traceBatch ->
            val startTrace = index * BATCH_SIZE + 1
            val endTrace = (index * BATCH_SIZE) + traceBatch.size
            logger.info("Processing batch ${index + 1} / $totalBatches. Traces $startTrace to $endTrace")

            val tracesData = traceBatch.mapIndexed { traceIdx, trace ->
                mapOf(
                    "traceId" to trace.traceNode.traceId,
                    "caseId" to trace.traceNode.caseId,
                    "createdAt" to trace.traceNode.createdAt,
                    "importOrder" to (index * BATCH_SIZE + traceIdx),
                    "attributes" to sanitizeAttributes(trace.traceNode.attributes),
                )
            }

            val eventsData = traceBatch.flatMap { trace ->
                trace.events.mapIndexed { eventIdx, event ->
                    mapOf(
                        "traceId" to trace.traceNode.traceId,
                        "eventId" to event.eventNode.eventId,
                        "activity" to event.eventNode.activity,
                        "timestamp" to event.eventNode.timestamp,
                        "resource" to event.eventNode.resource,
                        "lifecycle" to event.eventNode.lifecycle,
                        "cost" to event.eventNode.cost,
                        "createdAt" to event.eventNode.createdAt,
                        "importOrder" to eventIdx,
                        "attributes" to sanitizeAttributes(event.eventNode.attributes),
                    )
                }
            }

            val followsData = traceBatch.flatMap { trace ->
                if (trace.events.size > 1) {
                    trace.events.sortedBy { it.eventNode.timestamp }.windowed(2).map { (from, to) ->
                        mapOf("fromEventId" to from.eventNode.eventId, "toEventId" to to.eventNode.eventId)
                    }
                } else {
                    emptyList()
                }
            }

            neo4jDriver.session().use { session ->
                session.executeWrite { tx ->
                    // Batch create Trace nodes and CONTAINS relationships
                    if (tracesData.isNotEmpty()) {
                        val createTracesQuery = """
                        UNWIND ${'$'}traces as traceProps
                        MATCH (log:Log {logId: ${'$'}logId})
                        CREATE (trace:Trace {
                            traceId: traceProps.traceId,
                            caseId: traceProps.caseId,
                            createdAt: traceProps.createdAt,
                            importOrder: traceProps.importOrder
                        })
                        SET trace += traceProps.attributes
                        CREATE (log)-[:CONTAINS]->(trace)
                    """
                        tx.run(createTracesQuery, mapOf("logId" to logId, "traces" to tracesData)).consume()
                    }

                    // Batch create Event nodes and HAS_EVENT relationships
                    if (eventsData.isNotEmpty()) {
                        val createEventsQuery = """
                        UNWIND ${'$'}events as eventProps
                        MATCH (trace:Trace {traceId: eventProps.traceId})
                        CREATE (event:Event {
                            eventId: eventProps.eventId,
                            activity: eventProps.activity,
                            timestamp: eventProps.timestamp,
                            resource: eventProps.resource,
                            lifecycle: eventProps.lifecycle,
                            cost: eventProps.cost,
                            createdAt: eventProps.createdAt,
                            importOrder: eventProps.importOrder
                        })
                        SET event += eventProps.attributes
                        CREATE (trace)-[:HAS_EVENT]->(event)
                    """
                        tx.run(createEventsQuery, mapOf("events" to eventsData)).consume()
                    }

                    // Batch create FOLLOWS relationships
                    if (followsData.isNotEmpty()) {
                        val createFollowsQuery = """
                        UNWIND ${'$'}follows as followPair
                        MATCH (from:Event {eventId: followPair.fromEventId})
                        MATCH (to:Event {eventId: followPair.toEventId})
                        CREATE (from)-[:FOLLOWS]->(to)
                    """
                        tx.run(createFollowsQuery, mapOf("follows" to followsData)).consume()
                    }
                }
            }
        }

        logger.info("Finished saving XES log to Neo4j: $logId")
        return logId
    }

    /**
     * Load XES file from classpath resource
     */
    fun loadXESFromResource(resourcePath: String, logId: String? = null): XESLoadResult {
        logger.info("Loading XES from resource: $resourcePath")

        return try {
            val inputStream = this::class.java.classLoader.getResourceAsStream(resourcePath)
                ?: throw IllegalArgumentException("Resource not found: $resourcePath")

            inputStream.use { stream ->
                loadXESFile(stream, logId)
            }
        } catch (e: Exception) {
            logger.error("Error loading XES from resource: $resourcePath", e)
            XESLoadResult(
                success = false,
                error = e.cause?.message ?: e.message ?: "Unknown error occurred",
                message = "Failed to load XES from resource",
            )
        }
    }

    /**
     * Get loading statistics
     */
    fun getLoadingStatistics(): Map<String, Any> {
        // TODO: Implement loading statistics tracking
        return mapOf(
            "totalLoadsAttempted" to 0,
            "successfulLoads" to 0,
            "failedLoads" to 0,
            "totalTracesLoaded" to 0,
            "totalEventsLoaded" to 0,
        )
    }
}

/**
 * Result of XES loading operation
 */
data class XESLoadResult(
    val success: Boolean,
    val logId: String? = null,
    val tracesCount: Int = 0,
    val eventsCount: Int = 0,
    val message: String,
    val error: String? = null,
    val filename: String? = null, // Added for context in the UI
    val timestamp: LocalDateTime = LocalDateTime.now(),
)
