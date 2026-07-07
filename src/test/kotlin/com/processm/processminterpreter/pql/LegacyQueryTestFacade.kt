package com.processm.processminterpreter.pql

import com.processm.processminterpreter.pql.error.PQLCompileError
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Test-only facade kept for integration suites that still exercise the query
 * pipeline through the old helper API.
 */
@Component
class LegacyQueryTestFacade(
    private val pqlQueryService: PqlQueryService,
) {
    private val logger = LoggerFactory.getLogger(LegacyQueryTestFacade::class.java)

    fun executeDataStoreQuery(
        pqlQuery: String,
        dataStoreId: String,
        logId: String? = null,
        defaultTraceLimit: Int? = null,
    ): DataStorePqlQueryResult =
        try {
            pqlQueryService.execute(
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
}

data class DataStorePqlQueryResult(
    val dataStoreId: String,
    val success: Boolean,
    val query: String,
    val cypherQuery: String? = null,
    val results: List<Map<String, Any?>> = emptyList(),
    val logs: List<com.processm.processminterpreter.xes.model.XesLog> = emptyList(),
    val resultCount: Int = 0,
    val error: String? = null,
) {
    fun first(): com.processm.processminterpreter.xes.model.XesLog = logs.first()

    fun count(): Int = logs.size

    fun isEmpty(): Boolean = logs.isEmpty()

    operator fun iterator(): Iterator<com.processm.processminterpreter.xes.model.XesLog> = logs.iterator()
}

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
    )

private const val NO_DEFAULT_TRACE_LIMIT: Int = -1
