package com.processm.processminterpreter.application.compatibility

import com.processm.processminterpreter.application.ports.ProcessMJsonFormatter
import com.processm.processminterpreter.application.ports.QueryJsonProjection
import com.processm.processminterpreter.application.ports.RemoteQueryExecutionResult
import com.processm.processminterpreter.application.ports.RemoteProcessMGateway
import com.processm.processminterpreter.application.query.ExecutePqlQueryRequest
import com.processm.processminterpreter.application.query.ExecutePqlQueryUseCase
import com.processm.processminterpreter.application.query.QueryResult
import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.error.PQLCompileError
import com.processm.processminterpreter.domain.pql.plan.HierarchicalLimits
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class VerifyPqlQueryUseCase(
    private val executeUseCase: ExecutePqlQueryUseCase,
    private val remoteProcessM: RemoteProcessMGateway,
    private val formatter: ProcessMJsonFormatter,
) {
    private val logger = LoggerFactory.getLogger(VerifyPqlQueryUseCase::class.java)

    fun verify(request: VerifyPqlQueryRequest): VerifyPqlQueryResult {
        val localResult = executeLocal(request)
        val remoteResult = remoteProcessM.executeQuery(
            query = request.query,
            remoteDataStoreId = request.remoteDataStoreId,
            includeTraces = request.includeTraces,
            includeEvents = request.includeEvents,
        )

        val localXesResults =
            if (localResult.logs.isNotEmpty()) {
                formatter.formatAsXesJson(
                    QueryJsonProjection(
                        logs = localResult.logs,
                        rows = localResult.results,
                        hasExplicitSelect = localResult.hasExplicitSelect,
                        selectAllScopes = localResult.selectAllScopes,
                        projectedTraceStandardAttributes = localResult.projectedTraceStandardAttributes,
                        includeTraces = request.includeTraces,
                        includeEvents = request.includeEvents,
                    ),
                )
            } else {
                emptyList()
            }

        val details = StringBuilder()
        details.append(
            "Local: ${if (localResult.success) "Success (${localResult.logs.size} logs)" else "Fail: ${localResult.error}"}\n",
        )
        details.append(
            "Remote: ${if (remoteResult.success) "Success (${remoteResult.resultCount} logs)" else "Fail: ${remoteResult.message}"}\n",
        )

        val comparison = compare(localResult, remoteResult, localXesResults)
        val match = comparison.match

        details.append("Comparison: ${comparison.summary}\n")
        if (comparison.differences.isNotEmpty()) {
            details.append("Differences:\n")
            comparison.differences.forEach { diff ->
                details.append("  - $diff\n")
            }
        }

        return VerifyPqlQueryResult(
            match = match,
            localSuccess = localResult.success,
            remoteSuccess = remoteResult.success,
            localCount = localResult.logs.size,
            remoteCount = remoteResult.resultCount,
            localResults = if (request.lightFormat) emptyList() else localXesResults,
            remoteResults = if (request.lightFormat) emptyList() else remoteResult.results,
            remoteRequestUrl = remoteResult.requestUrl,
            remoteAdaptedQuery = remoteResult.adaptedQuery,
            remoteDataStoreId = remoteResult.remoteDataStoreId,
            details = details.toString(),
        )
    }

    private fun executeLocal(request: VerifyPqlQueryRequest): LocalVerificationResult =
        try {
            executeUseCase.execute(
                ExecutePqlQueryRequest(
                    query = request.query,
                    logId = request.logId,
                    dataStoreId = request.dataStoreId,
                    defaultLimits = request.defaultLimits,
                    materializedScopes = requestedScopes(request.includeTraces, request.includeEvents),
                ),
            ).toLocalVerificationResult(request.query)
        } catch (e: PQLCompileError) {
            logger.warn("Local query compilation failed: {}", e.message)
            LocalVerificationResult(
                success = false,
                query = request.query,
                error = e.message ?: e::class.simpleName ?: "Compile error",
            )
        } catch (e: Exception) {
            logger.warn("Local execution failed: ${e.message}")
            LocalVerificationResult(
                success = false,
                query = request.query,
                error = "Local Syntax/Execution Error: ${e.message}",
            )
        }

    private fun compare(
        localResult: LocalVerificationResult,
        remoteResult: RemoteQueryExecutionResult,
        localResults: List<Map<String, Any?>>,
    ): ComparisonResult {
        if (localResult.success && remoteResult.success) {
            return XESJsonComparator.compare(localResults, remoteResult.results)
        }
        if (!localResult.success && !remoteResult.success) {
            return compareFailures(localResult.error, remoteResult.message)
        }
        return ComparisonResult(
            match = false,
            traceCountLocal = localResult.logs.size,
            traceCountRemote = remoteResult.resultCount,
            differences = listOf("Execution outcome differs: LOCAL=${localResult.error}, REMOTE=${remoteResult.message}"),
            summary = "MISMATCH: one execution failed",
        )
    }

    private fun compareFailures(
        localMessage: String?,
        remoteMessage: String?,
    ): ComparisonResult {
        val localKind = PqlFailureKind.from(localMessage)
        val remoteKind = PqlFailureKind.from(remoteMessage)
        if (localKind != null && localKind == remoteKind) {
            return ComparisonResult(
                match = true,
                traceCountLocal = 0,
                traceCountRemote = 0,
                differences = emptyList(),
                summary = "MATCH: both executions failed with ${localKind.description}",
            )
        }
        return ComparisonResult(
            match = false,
            traceCountLocal = 0,
            traceCountRemote = 0,
            differences = listOf("Failure differs: LOCAL=$localMessage, REMOTE=$remoteMessage"),
            summary = "MISMATCH: both executions failed differently",
        )
    }
}

private data class LocalVerificationResult(
    val success: Boolean,
    val query: String,
    val error: String? = null,
    val logs: List<XesLog> = emptyList(),
    val results: List<Map<String, Any?>> = emptyList(),
    val resultCount: Int = 0,
    val hasExplicitSelect: Boolean = false,
    val selectAllScopes: Set<Scope> = emptySet(),
    val projectedTraceStandardAttributes: Set<String> = emptySet(),
)

private fun QueryResult.toLocalVerificationResult(query: String): LocalVerificationResult =
    LocalVerificationResult(
        success = true,
        query = query,
        logs = logs,
        results = rows,
        resultCount = rowCount,
        hasExplicitSelect = hasExplicitSelect,
        selectAllScopes = selectAllScopes,
        projectedTraceStandardAttributes = projectedTraceStandardAttributes,
    )

private enum class PqlFailureKind(val description: String) {
    POSITIVE_INTEGER_REQUIRED("positive integer required"),
    SCOPE_REQUIRED("scope required");

    companion object {
        fun from(message: String?): PqlFailureKind? {
            val text = message ?: return null
            return when {
                text.contains("PositiveIntegerRequired", ignoreCase = true) ||
                    text.contains("positive integer", ignoreCase = true) -> POSITIVE_INTEGER_REQUIRED
                text.contains("ScopeRequired", ignoreCase = true) ||
                    (text.contains("scope", ignoreCase = true) && text.contains("required", ignoreCase = true)) ->
                    SCOPE_REQUIRED
                else -> null
            }
        }
    }
}

private fun requestedScopes(includeTraces: Boolean, includeEvents: Boolean): Set<Scope> =
    buildSet {
        add(Scope.LOG)
        if (includeTraces || includeEvents) add(Scope.TRACE)
        if (includeEvents) add(Scope.EVENT)
    }

data class VerifyPqlQueryRequest(
    val query: String,
    val logId: String? = null,
    val dataStoreId: String? = null,
    val remoteDataStoreId: String? = null,
    val includeTraces: Boolean = false,
    val includeEvents: Boolean = false,
    val defaultLimits: HierarchicalLimits = HierarchicalLimits(),
    val lightFormat: Boolean = false,
)

data class VerifyPqlQueryResult(
    val match: Boolean,
    val localSuccess: Boolean,
    val remoteSuccess: Boolean,
    val localCount: Int = 0,
    val remoteCount: Int = 0,
    val localResults: List<Map<String, Any?>> = emptyList(),
    val remoteResults: List<Map<String, Any?>> = emptyList(),
    val remoteRequestUrl: String? = null,
    val remoteAdaptedQuery: String? = null,
    val remoteDataStoreId: String? = null,
    val details: String,
)
