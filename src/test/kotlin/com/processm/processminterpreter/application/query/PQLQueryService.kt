package com.processm.processminterpreter.application.query

import com.processm.processminterpreter.domain.pql.error.PQLCompileError
import com.processm.processminterpreter.domain.pql.common.HierarchicalLimits
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Test-only facade kept for integration suites that still exercise the query
 * pipeline through the old helper API.
 */
@Component
class PQLQueryService(
    private val executeUseCase: ExecutePqlQueryUseCase,
    private val validateUseCase: ValidatePqlQueryUseCase,
) {
    private val logger = LoggerFactory.getLogger(PQLQueryService::class.java)

    fun executeDataStoreQuery(
        pqlQuery: String,
        dataStoreId: String,
        logId: String? = null,
        defaultTraceLimit: Int? = null,
    ): DataStorePqlQueryResult =
        try {
            executeUseCase.execute(
                ExecutePqlQueryRequest(
                    query = pqlQuery,
                    logId = logId,
                    dataStoreId = dataStoreId,
                    defaultLimits = HierarchicalLimits(trace = defaultTraceLimit.toDomainTraceLimit()),
                ),
            ).toDataStorePqlQueryResult(query = pqlQuery, dataStoreId = dataStoreId)
        } catch (e: PQLCompileError) {
            logger.warn("PQL compile error: {}", e.message)
            DataStorePqlQueryResult(
                dataStoreId = dataStoreId,
                success = false,
                query = pqlQuery,
                error = e.message ?: e::class.simpleName ?: "Compile error",
            )
        } catch (e: Exception) {
            logger.error("Error executing PQL query: {}", pqlQuery, e)
            DataStorePqlQueryResult(
                dataStoreId = dataStoreId,
                success = false,
                query = pqlQuery,
                error = e.message ?: "Unknown error",
            )
        }

    fun validatePQLQuery(pqlQuery: String): PQLValidationResult {
        val result = validateUseCase.validate(ValidatePqlQueryRequest(query = pqlQuery))
        return if (result.valid) {
            PQLValidationResult(
                valid = true,
                query = pqlQuery,
                cypherQuery = null,
                message = "Query is valid",
            )
        } else {
            PQLValidationResult(
                valid = false,
                query = pqlQuery,
                error = result.errors.joinToString("\n"),
                message = "Query validation failed",
            )
        }
    }
}

data class DataStorePqlQueryResult(
    val dataStoreId: String,
    val success: Boolean,
    val query: String,
    val cypherQuery: String? = null,
    val results: List<Map<String, Any?>> = emptyList(),
    val logs: List<com.processm.processminterpreter.domain.log.xes.XesLog> = emptyList(),
    val resultCount: Int = 0,
    val executionTimeMs: Long = 0,
    val error: String? = null,
) {
    fun first(): com.processm.processminterpreter.domain.log.xes.XesLog = logs.first()

    fun count(): Int = logs.size

    fun isEmpty(): Boolean = logs.isEmpty()

    operator fun iterator(): Iterator<com.processm.processminterpreter.domain.log.xes.XesLog> = logs.iterator()
}

data class PQLValidationResult(
    val valid: Boolean,
    val query: String,
    val cypherQuery: String? = null,
    val message: String,
    val error: String? = null,
)

private fun Int?.toDomainTraceLimit(): Long? = when (this) {
    null -> null
    NO_DEFAULT_TRACE_LIMIT -> null
    else -> toLong()
}

private fun QueryResult.toDataStorePqlQueryResult(
    query: String,
    dataStoreId: String,
): DataStorePqlQueryResult =
    DataStorePqlQueryResult(
        dataStoreId = dataStoreId,
        success = true,
        query = query,
        cypherQuery = executedQueryDescription,
        results = rows,
        logs = logs,
        resultCount = rowCount,
        executionTimeMs = 0,
    )

private const val NO_DEFAULT_TRACE_LIMIT: Int = -1
