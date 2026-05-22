package com.processm.processminterpreter.application.query

import org.springframework.stereotype.Component

@Component
class GetPqlQueryMetadataUseCase {
    fun statistics(): PqlQueryStatisticsSummary =
        PqlQueryStatisticsSummary(
            totalQueries = 0,
            successfulQueries = 0,
            failedQueries = 0,
            averageExecutionTime = 0.0,
        )

    fun supportedFeatures(): SupportedPqlFeatures =
        SupportedPqlFeatures(
            supportedClauses = listOf("SELECT", "FROM", "WHERE"),
            supportedOperators = listOf("=", "!=", "<>", "<", ">", "<=", ">=", "LIKE"),
            supportedEntities = listOf("log", "trace", "event"),
            supportedFields =
                mapOf(
                    "log" to listOf("id", "logId", "name", "createdAt", "updatedAt", "attributes"),
                    "trace" to listOf("id", "traceId", "caseId", "createdAt", "attributes"),
                    "event" to
                        listOf(
                            "id",
                            "eventId",
                            "activity",
                            "timestamp",
                            "resource",
                            "lifecycle",
                            "cost",
                            "createdAt",
                            "attributes",
                        ),
                ),
            limitations =
                listOf(
                    "Complex joins not yet supported",
                    "Subqueries not yet supported",
                ),
            examples =
                listOf(
                    "SELECT * FROM log",
                    "SELECT * FROM trace WHERE caseId = 'case-123'",
                    "SELECT activity, timestamp FROM event WHERE activity = 'Task A'",
                    "SELECT * FROM event WHERE resource LIKE 'John' AND timestamp > '2023-01-01'",
                    "SELECT t:caseId, count(e:id) GROUP BY t:caseId",
                    "SELECT avg(e:cost:total) WHERE e:activity = 'Surgery'",
                ),
        )
}

data class PqlQueryStatisticsSummary(
    val totalQueries: Int,
    val successfulQueries: Int,
    val failedQueries: Int,
    val averageExecutionTime: Double,
)

data class SupportedPqlFeatures(
    val supportedClauses: List<String>,
    val supportedOperators: List<String>,
    val supportedEntities: List<String>,
    val supportedFields: Map<String, List<String>>,
    val limitations: List<String>,
    val examples: List<String>,
)
