package com.processm.processminterpreter.application.query

import org.springframework.stereotype.Component

@Component
class PqlMetadataUseCase {
    /**
     * Runtime query metrics are not collected yet. Keep this endpoint explicit
     * instead of mixing static language metadata with fake counters.
     */
    fun statistics(): PqlQueryStatisticsSummary =
        PqlQueryStatisticsSummary(
            totalQueries = 0,
            successfulQueries = 0,
            failedQueries = 0,
            averageExecutionTime = 0.0,
        )

    fun supportedFeatures(): SupportedPqlFeatures =
        SupportedPqlFeatures(
            supportedClauses = listOf("SELECT", "WHERE", "GROUP BY", "ORDER BY", "LIMIT", "OFFSET", "DELETE"),
            supportedOperators = listOf("=", "!=", "<>", "<", ">", "<=", ">=", "LIKE", "MATCHES", "IN", "IS NULL"),
            supportedEntities = listOf("log", "trace", "event", "classifier"),
            supportedFields =
                mapOf(
                    "log" to listOf("l:name", "l:id", "l:*", "[l:custom]"),
                    "trace" to listOf("t:name", "t:id", "t:total", "t:currency", "t:*", "[t:custom]"),
                    "event" to
                        listOf(
                            "e:name",
                            "e:id",
                            "e:timestamp",
                            "e:resource",
                            "e:transition",
                            "e:total",
                            "e:currency",
                            "e:*",
                            "[e:custom]",
                        ),
                ),
            limitations =
                listOf(
                    "Authentication and sessions are intentionally not implemented in the local compatibility API",
                    "Remote comparison depends on the configured external ProcessM instance",
                ),
            examples =
                listOf(
                    "limit e:3, t:2, l:1",
                    "where l:name = 'teleclaims.mxml'",
                    "select l:name, t:name, e:name, e:timestamp limit l:1, t:2, e:3",
                    "select min(e:timestamp), max(e:timestamp)",
                    "select count(t:name) group by ^e:name order by count(t:name) desc",
                    "delete where l:name = 'temporary-log'",
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
