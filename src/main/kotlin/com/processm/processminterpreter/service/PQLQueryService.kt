package com.processm.processminterpreter.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.processm.processminterpreter.model.hierarchical.Log
import com.processm.processminterpreter.pql.CypherQuery
import com.processm.processminterpreter.pql.PQLTranslator
import com.processm.processminterpreter.util.HierarchyReconstructor
import com.processm.processminterpreter.xes.XESWriter
import org.neo4j.driver.Driver
import org.neo4j.driver.Record
import org.neo4j.driver.Result
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

import java.io.ByteArrayOutputStream

/**
 * Service for executing PQL queries
 *
 * Translates PQL queries to Cypher and executes them against Neo4j database
 */
@Service
class PQLQueryService(
    private val pqlTranslator: PQLTranslator,
    private val neo4jDriver: Driver,
    private val xesWriter: XESWriter,
) {
    private val logger = LoggerFactory.getLogger(PQLQueryService::class.java)
    private val objectMapper = ObjectMapper()

    /**
     * Fetch classifier definitions from the Log node in Neo4j.
     * Returns a map of classifier name → list of XES attribute keys.
     */
    private fun fetchClassifiers(logId: String?): Map<String, List<String>> {
        if (logId == null) return emptyMap()
        return try {
            neo4jDriver.session().use { session ->
                val result =
                    session.run(
                        "MATCH (log:Log {logId: \$logId}) RETURN log.classifiers AS classifiers",
                        mapOf("logId" to logId),
                    )
                if (result.hasNext()) {
                    val json = result.single().get("classifiers").asString(null)
                    if (json != null) {
                        objectMapper.readValue(json, object : TypeReference<Map<String, List<String>>>() {})
                    } else {
                        emptyMap()
                    }
                } else {
                    emptyMap()
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch classifiers for logId=$logId: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Execute PQL query and return results
     */
    fun executePQLQuery(
        pqlQuery: String,
        logId: String? = null,
        defaultTraceLimit: Int? = null,
    ): PQLQueryResult {
        logger.info("Executing PQL query: $pqlQuery (logId: $logId)")

        return try {
            // Fetch classifier definitions from the log node
            val classifiers = fetchClassifiers(logId)

            // Translate PQL to Cypher
            val cypherQuery = pqlTranslator.translateToCypher(pqlQuery, logId, classifiers, defaultTraceLimit)
            logger.debug("Translated to Cypher: {}", cypherQuery)

            // DELETE queries need a write transaction
            if (cypherQuery.isDelete) {
                val deletedCount =
                    neo4jDriver.session().use { session ->
                        session.executeWrite { tx ->
                            val result = tx.run(cypherQuery.query, cypherQuery.parameters)
                            result.consume().counters().nodesDeleted()
                        }
                    }
                logger.info("DELETE query removed $deletedCount nodes")
                return PQLQueryResult(
                    success = true,
                    query = pqlQuery,
                    cypherQuery = cypherQuery.query,
                    results = emptyList(),
                    logs = emptyList(),
                    resultCount = deletedCount,
                    executionTimeMs = 0,
                )
            }

            // Execute Cypher query (flat results)
            val flatResults = executeCypherQuery(cypherQuery)

            // Reconstruct hierarchical structure with limits
            val hierarchicalLogs =
                HierarchyReconstructor.reconstruct(
                    flatResults,
                    cypherQuery.hierarchicalLimits,
                    cypherQuery.columnAliases,
                    cypherQuery.isAggregationResult,
                    cypherQuery.hasTraceOrderBy,
                )

            PQLQueryResult(
                success = true,
                query = pqlQuery,
                cypherQuery = cypherQuery.query,
                results = flatResults,
                logs = hierarchicalLogs,
                resultCount = flatResults.size,
                executionTimeMs = 0, // TODO: Add timing
                hasExplicitTraceSelect = cypherQuery.hasExplicitTraceSelect,
            )
        } catch (e: Exception) {
            logger.error("Error executing PQL query: $pqlQuery", e)
            PQLQueryResult(
                success = false,
                query = pqlQuery,
                error = e.message ?: "Unknown error",
                results = emptyList(),
                resultCount = 0,
                executionTimeMs = 0,
            )
        }
    }

    /**
     * Validate PQL query syntax
     */
    fun validatePQLQuery(pqlQuery: String): PQLValidationResult {
        logger.debug("Validating PQL query: $pqlQuery")

        return try {
            // Try to translate the query
            val cypherQuery = pqlTranslator.translateToCypher(pqlQuery)

            PQLValidationResult(
                valid = true,
                query = pqlQuery,
                cypherQuery = cypherQuery.query,
                message = "Query is valid",
            )
        } catch (e: Exception) {
            logger.warn("Invalid PQL query: $pqlQuery - ${e.message}")
            PQLValidationResult(
                valid = false,
                query = pqlQuery,
                error = e.message ?: "Unknown validation error",
                message = "Query validation failed",
            )
        }
    }

    /**
     * Execute Cypher query against Neo4j
     */
    private fun executeCypherQuery(cypherQuery: CypherQuery): List<Map<String, Any?>> {
        neo4jDriver.session().use { session ->
            val result: Result = session.run(cypherQuery.query, cypherQuery.parameters)

            return result.list { record ->
                convertRecordToMap(record)
            }
        }
    }

    /**
     * Convert Neo4j Record to Map
     */
    private fun convertRecordToMap(record: Record): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()

        record.keys().forEach { key ->
            val value = record.get(key)
            map[key] =
                when {
                    value.isNull -> {
                        null
                    }

                    value.hasType(
                        org.neo4j.driver.types.TypeSystem
                            .getDefault()
                            .NODE(),
                    ) -> {
                        // Convert Node to Map
                        val node = value.asNode()
                        val nodeMap = mutableMapOf<String, Any?>()
                        nodeMap["id"] = node.elementId()
                        nodeMap["labels"] = node.labels().toList()
                        node.keys().forEach { nodeKey ->
                            nodeMap[nodeKey] = convertNeo4jValue(node.get(nodeKey))
                        }
                        nodeMap
                    }

                    value.hasType(
                        org.neo4j.driver.types.TypeSystem
                            .getDefault()
                            .RELATIONSHIP(),
                    ) -> {
                        // Convert Relationship to Map
                        val rel = value.asRelationship()
                        val relMap = mutableMapOf<String, Any?>()
                        relMap["id"] = rel.elementId()
                        relMap["type"] = rel.type()
                        relMap["startNodeId"] = rel.startNodeElementId()
                        relMap["endNodeId"] = rel.endNodeElementId()
                        rel.keys().forEach { relKey ->
                            relMap[relKey] = convertNeo4jValue(rel.get(relKey))
                        }
                        relMap
                    }

                    else -> {
                        convertNeo4jValue(value)
                    }
                }
        }

        return map
    }

    /**
     * Convert Neo4j Value to Java object
     */
    private fun convertNeo4jValue(value: org.neo4j.driver.Value): Any? =
        when {
            value.isNull -> {
                null
            }

            value.hasType(
                org.neo4j.driver.types.TypeSystem
                    .getDefault()
                    .STRING(),
            ) -> {
                value.asString()
            }

            value.hasType(
                org.neo4j.driver.types.TypeSystem
                    .getDefault()
                    .INTEGER(),
            ) -> {
                value.asLong()
            }

            value.hasType(
                org.neo4j.driver.types.TypeSystem
                    .getDefault()
                    .FLOAT(),
            ) -> {
                value.asDouble()
            }

            value.hasType(
                org.neo4j.driver.types.TypeSystem
                    .getDefault()
                    .BOOLEAN(),
            ) -> {
                value.asBoolean()
            }

            value.hasType(
                org.neo4j.driver.types.TypeSystem
                    .getDefault()
                    .DATE_TIME(),
            ) -> {
                value.asZonedDateTime()
            }

            value.hasType(
                org.neo4j.driver.types.TypeSystem
                    .getDefault()
                    .LOCAL_DATE_TIME(),
            ) -> {
                value.asLocalDateTime()
            }

            value.hasType(
                org.neo4j.driver.types.TypeSystem
                    .getDefault()
                    .DATE(),
            ) -> {
                value.asLocalDate()
            }

            value.hasType(
                org.neo4j.driver.types.TypeSystem
                    .getDefault()
                    .TIME(),
            ) -> {
                value.asOffsetTime()
            }

            value.hasType(
                org.neo4j.driver.types.TypeSystem
                    .getDefault()
                    .LOCAL_TIME(),
            ) -> {
                value.asLocalTime()
            }

            value.hasType(
                org.neo4j.driver.types.TypeSystem
                    .getDefault()
                    .LIST(),
            ) -> {
                value.asList { convertNeo4jValue(it) }
            }

            value.hasType(
                org.neo4j.driver.types.TypeSystem
                    .getDefault()
                    .MAP(),
            ) -> {
                val map = mutableMapOf<String, Any?>()
                value.asMap().forEach { (k, v) ->
                    map[k] =
                        convertNeo4jValue(
                            org.neo4j.driver.Values
                                .value(v),
                        )
                }
                map
            }

            else -> {
                value.asObject()
            }
        }

    /**
     * Execute PQL query and return results as XES XML
     *
     * @param pqlQuery PQL query string
     * @param logId Optional log ID filter
     * @param compress Whether to gzip compress the output
     * @return XES XML as byte array
     */
    fun executePQLQueryAsXES(
        pqlQuery: String,
        logId: String? = null,
        compress: Boolean = false,
        logName: String = "Query Result Log",
    ): ByteArray {
        logger.info("Executing PQL query as XES: $pqlQuery (logId: $logId, compress: $compress)")

        return try {
            // Translate PQL to Cypher
            val cypherQuery = pqlTranslator.translateToCypher(pqlQuery, logId)
            logger.debug("Translated to Cypher: {}", cypherQuery)

            // Execute Cypher query
            val results = executeCypherQuery(cypherQuery)

            // Convert results to XES
            val outputStream = ByteArrayOutputStream()
            xesWriter.writeXES(results, outputStream, compress, logName)

            outputStream.toByteArray()
        } catch (e: Exception) {
            logger.error("Error executing PQL query as XES: $pqlQuery", e)
            throw e
        }
    }

    /**
     * Get query execution statistics
     */
    fun getQueryStatistics(): Map<String, Any> {
        // TODO: Implement query statistics tracking
        return mapOf(
            "totalQueries" to 0,
            "successfulQueries" to 0,
            "failedQueries" to 0,
            "averageExecutionTime" to 0.0,
        )
    }
}

/**
 * Result of PQL query execution
 *
 * Contains both flat results (for backward compatibility) and hierarchical logs (ProcessM compatible)
 */
data class PQLQueryResult(
    val success: Boolean,
    val query: String,
    val cypherQuery: String? = null,
    val results: List<Map<String, Any?>> = emptyList(),
    val logs: List<Log> = emptyList(),
    val resultCount: Int = 0,
    val executionTimeMs: Long = 0,
    val error: String? = null,
    val hasExplicitTraceSelect: Boolean = false,
) {
    /**
     * Get first log (convenience method for tests)
     */
    fun first(): Log = logs.first()

    /**
     * Get count of logs
     */
    fun count(): Int = logs.size

    /**
     * Check if result is empty
     */
    fun isEmpty(): Boolean = logs.isEmpty()

    /**
     * Iterate over logs (for compatibility with ProcessM DBHierarchicalXESInputStream)
     */
    operator fun iterator(): Iterator<Log> = logs.iterator()
}

/**
 * Result of PQL query validation
 */
data class PQLValidationResult(
    val valid: Boolean,
    val query: String,
    val cypherQuery: String? = null,
    val message: String,
    val error: String? = null,
)
