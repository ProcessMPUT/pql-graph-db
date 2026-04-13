package com.processm.processminterpreter.service

import com.processm.processminterpreter.model.LogNode
import org.neo4j.driver.Driver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

@Service
class LogService(
    private val driver: Driver,
) {
    private val logger = LoggerFactory.getLogger(LogService::class.java)

    fun createLog(
        logId: String,
        name: String,
        attributes: Map<String, Any> = emptyMap(),
    ): LogNode {
        require(logId.isNotBlank()) { "Log ID cannot be blank" }
        require(name.isNotBlank()) { "Log name cannot be blank" }

        if (logExists(logId)) {
            throw IllegalArgumentException("Log with ID '$logId' already exists")
        }

        val now = LocalDateTime.now()
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run(
                    """
                    CREATE (log:Log {
                        logId: ${'$'}logId,
                        name: ${'$'}name,
                        createdAt: ${'$'}createdAt,
                        updatedAt: ${'$'}updatedAt
                    })
                    SET log += ${'$'}attributes
                    """.trimIndent(),
                    mapOf("logId" to logId, "name" to name, "createdAt" to now, "updatedAt" to now, "attributes" to attributes),
                )
            }
        }
        logger.info("Created log '$logId'")
        return LogNode(logId = logId, name = name, createdAt = now, updatedAt = now, attributes = attributes)
    }

    fun getLogById(logId: String): LogNode =
        driver.session().use { session ->
            session.executeRead { tx ->
                val result = tx.run("MATCH (log:Log {logId: \$logId}) RETURN log", mapOf("logId" to logId))
                if (result.hasNext()) result.single().get("log").asNode().toLogNode()
                else throw LogNotFoundException("Log with ID '$logId' not found")
            }
        }

    fun getLogWithStatistics(logId: String): LogStatistics =
        driver.session().use { session ->
            session.executeRead { tx ->
                val result =
                    tx.run(
                        """
                        MATCH (log:Log {logId: ${'$'}logId})
                        OPTIONAL MATCH (log)-[:CONTAINS]->(trace:Trace)
                        OPTIONAL MATCH (trace)-[:HAS_EVENT]->(event:Event)
                        RETURN log, count(DISTINCT trace) as traceCount, count(DISTINCT event) as eventCount
                        """.trimIndent(),
                        mapOf("logId" to logId),
                    )
                if (result.hasNext()) {
                    val record = result.single()
                    LogStatistics(
                        log = record.get("log").asNode().toLogNode(),
                        traceCount = record.get("traceCount").asLong(),
                        eventCount = record.get("eventCount").asLong(),
                    )
                } else {
                    throw LogNotFoundException("Log with ID '$logId' not found")
                }
            }
        }

    fun getAllLogs(): List<LogNode> =
        driver.session().use { session ->
            session.executeRead { tx ->
                tx
                    .run("MATCH (log:Log) RETURN log ORDER BY log.createdAt DESC")
                    .list { it.get("log").asNode().toLogNode() }
            }
        }

    fun getAllLogsWithStatistics(): List<LogWithStatistics> =
        driver.session().use { session ->
            session.executeRead { tx ->
                tx
                    .run(
                        """
                        MATCH (log:Log)
                        OPTIONAL MATCH (log)-[:CONTAINS]->(trace:Trace)
                        OPTIONAL MATCH (trace)-[:HAS_EVENT]->(event:Event)
                        RETURN log, count(DISTINCT trace) as traceCount, count(DISTINCT event) as eventCount
                        ORDER BY log.createdAt DESC
                        """.trimIndent(),
                    ).list { record ->
                        LogWithStatistics(
                            log = record.get("log").asNode().toLogNode(),
                            traceCount = record.get("traceCount").asLong(),
                            eventCount = record.get("eventCount").asLong(),
                        )
                    }
            }
        }

    fun searchLogsByName(name: String): List<LogNode> {
        require(name.isNotBlank()) { "Search name cannot be blank" }
        return driver.session().use { session ->
            session.executeRead { tx ->
                tx
                    .run(
                        "MATCH (log:Log) WHERE toLower(log.name) CONTAINS toLower(\$name) RETURN log",
                        mapOf("name" to name),
                    ).list { it.get("log").asNode().toLogNode() }
            }
        }
    }

    fun getLogsCreatedAfter(date: LocalDateTime): List<LogNode> =
        driver.session().use { session ->
            session.executeRead { tx ->
                tx
                    .run(
                        "MATCH (log:Log) WHERE log.createdAt > \$date RETURN log ORDER BY log.createdAt DESC",
                        mapOf("date" to date),
                    ).list { it.get("log").asNode().toLogNode() }
            }
        }

    fun getLogsCreatedBetween(
        startDate: LocalDateTime,
        endDate: LocalDateTime,
    ): List<LogNode> {
        require(startDate.isBefore(endDate)) { "Start date must be before end date" }
        return driver.session().use { session ->
            session.executeRead { tx ->
                tx
                    .run(
                        "MATCH (log:Log) WHERE log.createdAt >= \$start AND log.createdAt <= \$end RETURN log ORDER BY log.createdAt DESC",
                        mapOf("start" to startDate, "end" to endDate),
                    ).list { it.get("log").asNode().toLogNode() }
            }
        }
    }

    fun updateLog(
        logId: String,
        name: String? = null,
        attributes: Map<String, Any>? = null,
    ): LogNode {
        val existing = getLogById(logId)
        val updatedName = name ?: existing.name
        val updatedAttributes = attributes ?: existing.attributes
        val updatedAt = LocalDateTime.now()

        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run(
                    """
                    MATCH (log:Log {logId: ${'$'}logId})
                    SET log.name = ${'$'}name, log.updatedAt = ${'$'}updatedAt
                    SET log += ${'$'}attributes
                    """.trimIndent(),
                    mapOf("logId" to logId, "name" to updatedName, "attributes" to updatedAttributes, "updatedAt" to updatedAt),
                )
            }
        }
        logger.info("Updated log '$logId'")
        return existing.copy(name = updatedName, attributes = updatedAttributes, updatedAt = updatedAt)
    }

    fun deleteLog(logId: String): Boolean {
        if (!logExists(logId)) throw LogNotFoundException("Log with ID '$logId' not found")
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run("MATCH (log:Log {logId: \$logId}) DETACH DELETE log", mapOf("logId" to logId))
            }
        }
        logger.info("Deleted log '$logId'")
        return true
    }

    fun deleteLogWithAllData(logId: String): Boolean {
        if (!logExists(logId)) throw LogNotFoundException("Log with ID '$logId' not found")
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run(
                    """
                    MATCH (log:Log {logId: ${'$'}logId})
                    OPTIONAL MATCH (log)-[:CONTAINS]->(trace:Trace)
                    OPTIONAL MATCH (trace)-[:HAS_EVENT]->(event:Event)
                    DETACH DELETE log, trace, event
                    """.trimIndent(),
                    mapOf("logId" to logId),
                )
            }
        }
        logger.info("Deleted log '$logId' with all data")
        return true
    }

    fun logExists(logId: String): Boolean =
        driver.session().use { session ->
            session.executeRead { tx ->
                tx
                    .run(
                        "MATCH (log:Log {logId: \$logId}) RETURN count(log) > 0 AS exists",
                        mapOf("logId" to logId),
                    ).single()
                    .get("exists")
                    .asBoolean()
            }
        }

    fun findLogsByAttribute(
        attributeKey: String,
        attributeValue: Any,
    ): List<LogNode> {
        require(attributeKey.isNotBlank()) { "Attribute key cannot be blank" }
        return driver.session().use { session ->
            session.executeRead { tx ->
                tx
                    .run(
                        "MATCH (log:Log) WHERE log[${'$'}attributeKey] = \$attributeValue RETURN log",
                        mapOf("attributeKey" to attributeKey, "attributeValue" to attributeValue),
                    ).list { it.get("log").asNode().toLogNode() }
            }
        }
    }

    fun generateLogId(): String {
        var logId: String
        do {
            logId = "log-${UUID.randomUUID().toString().substring(0, 8)}"
        } while (logExists(logId))
        return logId
    }

    private fun org.neo4j.driver.types.Node.toLogNode(): LogNode {
        val structural = setOf("logId", "name", "createdAt", "updatedAt")
        val attributes =
            keys()
                .filter { it !in structural }
                .associateWith { key -> get(key).asObject() }
        return LogNode(
            logId = get("logId").asString(),
            name = get("name").asString(),
            createdAt = get("createdAt").asLocalDateTime(),
            updatedAt = get("updatedAt").asLocalDateTime(),
            attributes = attributes,
        )
    }
}

data class LogStatistics(
    val log: LogNode,
    val traceCount: Long,
    val eventCount: Long,
)

data class LogWithStatistics(
    val log: LogNode,
    val traceCount: Long,
    val eventCount: Long,
)

class LogNotFoundException(
    message: String,
) : RuntimeException(message)