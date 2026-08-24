package com.processm.processminterpreter.processm.compat

import com.processm.processminterpreter.processm.json.QueryJsonProjection
import com.processm.processminterpreter.processm.json.requestedScopes
import com.processm.processminterpreter.processm.RemoteQueryExecutionResult
import com.processm.processminterpreter.processm.RemoteProcessMGateway
import com.processm.processminterpreter.pql.ExecutePqlQueryRequest
import com.processm.processminterpreter.pql.XesAttributeReadMode
import com.processm.processminterpreter.pql.PqlQueryService
import com.processm.processminterpreter.pql.QueryResult
import com.processm.processminterpreter.processm.json.ProcessMXesJsonFormatter
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.error.PQLCompileError
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

@Component
class VerificationService(
    private val pqlQueryService: PqlQueryService,
    private val remoteProcessM: RemoteProcessMGateway,
    private val formatter: ProcessMXesJsonFormatter,
) {
    private val logger = LoggerFactory.getLogger(VerificationService::class.java)
    private val hoistedEventGroupBy = Regex("""(?is)\bgroup\s+by\b.*\^(?:e|event)\s*:""")
    private val eventGroupBy = Regex("""(?is)\bgroup\s+by\b.*(?:^|[,\s])(?:e|event)\s*:""")
    private val orderBy = Regex("""(?is)\border\s+by\b""")

    fun verify(request: VerifyPqlQueryRequest): VerifyPqlQueryResult {
        // Local (Neo4j) and remote (HTTP) executions are independent — overlap
        // them: remote on a pooled thread, local on the caller thread.
        val remoteFuture = CompletableFuture.supplyAsync {
            remoteProcessM.executeQuery(
                query = request.query,
                remoteDataStoreId = request.remoteDataStoreId,
                includeTraces = request.includeTraces,
                includeEvents = request.includeEvents,
            )
        }
        val localResult = executeLocal(request)
        val remoteResult = try {
            remoteFuture.join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }

        val localXesResults = formatLocalResult(localResult, request)

        val details = StringBuilder()
        details.append(
            "Local: ${if (localResult.success) "Success (${localResult.logs.size} logs)" else "Fail: ${localResult.error}"}\n",
        )
        details.append(
            "Remote: ${if (remoteResult.success) "Success (${remoteResult.resultCount} logs)" else "Fail: ${remoteResult.message}"}\n",
        )

        val comparison = compare(request, localResult, remoteResult, localXesResults)
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
            comparisonStatus = comparison.status.name,
            details = details.toString(),
        )
    }

    private fun formatLocalResult(
        localResult: LocalVerificationResult,
        request: VerifyPqlQueryRequest,
    ): List<Map<String, Any?>> =
        if (localResult.logs.isNotEmpty()) {
            formatter.formatAsXesJson(
                QueryJsonProjection(
                    logs = localResult.logs,
                    hasExplicitSelect = localResult.hasExplicitSelect,
                    selectAllScopes = localResult.selectAllScopes,
                    projectedLogAttributes = localResult.projectedLogAttributes,
                    projectedTraceStandardAttributes = localResult.projectedTraceStandardAttributes,
                    includeTraces = request.includeTraces,
                    includeEvents = request.includeEvents,
                ),
            )
        } else {
            emptyList()
        }

    private fun executeLocal(request: VerifyPqlQueryRequest): LocalVerificationResult =
        try {
            pqlQueryService.execute(
                ExecutePqlQueryRequest(
                    query = request.query,
                    logId = request.logId,
                    dataStoreId = request.dataStoreId,
                    defaultLimits = request.defaultLimits,
                    materializedScopes = requestedScopes(request.includeTraces, request.includeEvents),
                    attributeReadMode = XesAttributeReadMode.PROCESSM_JSON,
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
        request: VerifyPqlQueryRequest,
        localResult: LocalVerificationResult,
        remoteResult: RemoteQueryExecutionResult,
        localResults: List<Map<String, Any?>>,
    ): ComparisonResult {
        if (localResult.success && remoteResult.success) {
            val strictComparison = XESJsonComparator.compare(localResults, remoteResult.results)
            if (strictComparison.match) {
                return strictComparison
            }

            val eventOrderComparison =
                if (canCheckUnstableGroupedEventOrder(request)) {
                    XESJsonComparator.compareIgnoringEventOrder(localResults, remoteResult.results)
                } else {
                    null
                }
            if (eventOrderComparison?.status == ComparisonStatus.NONDETERMINISTIC_MATCH) {
                return eventOrderComparison
            }

            val canTryCompatibilityFallback =
                (
                    canCheckUnstableTraceVariantWindow(request) &&
                        XESJsonComparator.hasOnlyTraceWindowDifferences(strictComparison)
                ) ||
                    eventOrderComparison?.let(XESJsonComparator::hasOnlyTraceWindowDifferences) == true
            if (!canTryCompatibilityFallback) {
                return strictComparison
            }

            val ignoreEventOrder = eventOrderComparison != null
            for (fallbackLimits in compatibilityFallbackLimits(request.defaultLimits, widenEvents = ignoreEventOrder)) {
                val fallbackLocalResult = executeLocal(request.copy(defaultLimits = fallbackLimits))
                if (!fallbackLocalResult.success) {
                    continue
                }

                val subsetComparison = XESJsonComparator.compareRemoteTraceSubset(
                    localLogs = fallbackLocalResult.logs,
                    remoteJson = remoteResult.results,
                    isProjectedQuery = fallbackLocalResult.hasExplicitSelect,
                    projectedTraceStandardAttributes = fallbackLocalResult.projectedTraceStandardAttributes,
                    includeEvents = request.includeEvents,
                    ignoreEventOrder = ignoreEventOrder,
                    allowEventSubset = ignoreEventOrder && fallbackLimits.event != request.defaultLimits.event,
                )
                if (subsetComparison.status == ComparisonStatus.NONDETERMINISTIC_MATCH) {
                    return subsetComparison
                }
            }
            return strictComparison
        }
        if (!localResult.success && !remoteResult.success) {
            return compareFailures(localResult.query, localResult.error, remoteResult.message)
        }
        return ComparisonResult(
            match = false,
            traceCountLocal = localResult.logs.size,
            traceCountRemote = remoteResult.resultCount,
            differences = listOf("Execution outcome differs: LOCAL=${localResult.error}, REMOTE=${remoteResult.message}"),
            summary = "MISMATCH: one execution failed",
        )
    }

    private fun canCheckUnstableTraceVariantWindow(request: VerifyPqlQueryRequest): Boolean =
        request.includeTraces &&
            hoistedEventGroupBy.containsMatchIn(request.query)

    private fun canCheckUnstableGroupedEventOrder(request: VerifyPqlQueryRequest): Boolean =
        request.includeEvents &&
            eventGroupBy.containsMatchIn(request.query) &&
            !orderBy.containsMatchIn(request.query)

    private fun compareFailures(
        query: String,
        localMessage: String?,
        remoteMessage: String?,
    ): ComparisonResult {
        val localKind = PqlFailureKind.from(query, localMessage)
        val remoteKind = PqlFailureKind.from(query, remoteMessage)
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
    val projectedLogAttributes: Set<String> = emptySet(),
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
        projectedLogAttributes = projectedLogAttributes,
        projectedTraceStandardAttributes = projectedTraceStandardAttributes,
    )

private enum class PqlFailureKind(val description: String) {
    POSITIVE_INTEGER_REQUIRED("positive integer required"),
    SCOPE_REQUIRED("scope required"),
    INVALID_CLASSIFIER("invalid classifier");

    companion object {
        fun from(query: String, message: String?): PqlFailureKind? {
            val text = message ?: return null
            return when {
                text.contains("PositiveIntegerRequired", ignoreCase = true) ||
                    text.contains("positive integer", ignoreCase = true) -> POSITIVE_INTEGER_REQUIRED
                text.contains("ScopeRequired", ignoreCase = true) ||
                    (text.contains("scope", ignoreCase = true) && text.contains("required", ignoreCase = true)) ->
                    SCOPE_REQUIRED
                text.contains("InvalidUseOfClassifiers", ignoreCase = true) ||
                    text.contains("Classifier", ignoreCase = true) && text.contains("not found", ignoreCase = true) ||
                    isRemoteClassifierStreamAbort(query, text) -> INVALID_CLASSIFIER
                else -> null
            }
        }

        private fun isRemoteClassifierStreamAbort(query: String, message: String): Boolean =
            query.contains(Regex("""(?i)\b(c|classifier):""")) &&
                message.contains("Unexpected end-of-input", ignoreCase = true) &&
                message.contains("expected close marker for Array", ignoreCase = true)
    }
}

private fun compatibilityFallbackLimits(
    defaultLimits: HierarchicalLimits,
    widenEvents: Boolean,
): List<HierarchicalLimits> {
    val traceLimit = defaultLimits.trace
    val eventLimit = defaultLimits.event.takeIf { widenEvents }
    if (traceLimit == null && eventLimit == null) return emptyList()

    val traceFallbacks = traceLimit
        ?.let { limit -> listOf(limit + maxOf(10, limit), limit * 5, null) }
        ?: listOf(null)
    val eventFallbacks = eventLimit
        ?.let { limit -> listOf(limit + maxOf(30, limit), limit * 5, null) }
        ?: listOf(defaultLimits.event)

    return traceFallbacks
        .flatMap { trace -> eventFallbacks.map { event -> defaultLimits.copy(trace = trace, event = event) } }
        .filter { it.trace != defaultLimits.trace || it.event != defaultLimits.event }
        .distinct()
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
    val comparisonStatus: String = ComparisonStatus.MISMATCH.name,
    val details: String,
)
