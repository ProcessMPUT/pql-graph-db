package com.processm.processminterpreter.pql

import com.processm.processminterpreter.neo4j.query.DeleteExecutionResult
import com.processm.processminterpreter.neo4j.query.ExecutionOptions
import com.processm.processminterpreter.neo4j.query.QueryExecutionResult
import com.processm.processminterpreter.xes.io.XesWriteOptions
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.pql.catalog.AttributeKind
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.error.PQLCompileError
import com.processm.processminterpreter.pql.plan.LogicalPlan
import com.processm.processminterpreter.pql.plan.Projection
import com.processm.processminterpreter.pql.ast.PqlExpression
import com.processm.processminterpreter.neo4j.query.Neo4jQueryPlanExecutor
import com.processm.processminterpreter.xes.io.OpenXesWriter
import org.springframework.stereotype.Service
import java.io.OutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Application service for everything PQL-query-shaped: execution, validation,
 * XES export, and static language metadata.
 *
 * The shared [PqlCompiler] owns parser and domain compilation semantics. This
 * service owns execution semantics: route a plan to the backend, return the
 * application result shape expected by controllers, and compose the auxiliary
 * flows (validate-only, export-as-XES) on top of the same pipeline.
 */
@Service
open class PqlQueryService(
    private val compiler: PqlCompiler,
    private val executor: Neo4jQueryPlanExecutor,
    private val writer: OpenXesWriter,
) {

    open fun execute(request: ExecutePqlQueryRequest): QueryResult {
        val prepared = compiler.prepareForExecution(
            query = request.query,
            logId = request.logId,
            dataStoreId = request.dataStoreId,
            defaultLimits = request.defaultLimits,
        )

        return when (prepared) {
            is PreparedPqlQuery.Single -> when (val plan = prepared.plan) {
                is LogicalPlan.Select -> {
                    val result = executor.execute(
                        plan,
                        ExecutionOptions(
                            defaultLimits = request.defaultLimits,
                            materializedScopes = request.materializedScopes,
                        ),
                    )
                    result.toQueryResult(plan.projection)
                }
                is LogicalPlan.Delete -> {
                    val result = executor.executeDelete(plan)
                    QueryResult(
                        logs = emptyList(),
                        rows = emptyList(),
                        rowCount = result.nodesDeleted,
                        executedQueryDescription = result.executedQueryDescription,
                    )
                }
            }
            is PreparedPqlQuery.PerLogSelect -> {
                val candidateLogIds = executor.findMatchingLogIds(prepared.candidateLogs)
                val plans = compiler.compilePerLog(prepared, candidateLogIds)
                // Per-log plans are independent read queries — run them concurrently.
                val perLogResults = plans.mapConcurrently(PER_LOG_PARALLELISM) { plan ->
                    plan to executor.execute(
                        plan,
                        ExecutionOptions(
                            defaultLimits = request.defaultLimits,
                            materializedScopes = request.materializedScopes,
                        ),
                    )
                }
                QueryResult(
                    logsProvider = {
                        applyLogWindow(
                            logs = perLogResults.flatMap { it.second.logs },
                            limit = prepared.outerLogLimit,
                            offset = prepared.outerLogOffset,
                        )
                    },
                    rows = perLogResults.flatMap { it.second.rows },
                    rowCount = perLogResults.sumOf { it.second.rowCount },
                    executedQueryDescription = perLogResults.joinToString("\n-- per-log --\n") {
                        it.second.executedQueryDescription
                    },
                    hasExplicitSelect = plans.any { it.projection.hasExplicitSelect() },
                    selectAllScopes = plans.flatMap { it.projection.selectedAllScopes() }.toSet(),
                    projectedLogAttributes = plans
                        .flatMap { it.projection.projectedLogAttributes() }
                        .toSet(),
                    projectedTraceStandardAttributes = plans
                        .flatMap { it.projection.projectedTraceStandardAttributes() }
                        .toSet(),
                )
            }
        }
    }

    /**
     * Read-only execution for query/export REST endpoints. A DELETE query is
     * refused BEFORE it reaches the executor, so a safe HTTP method (GET) can
     * never mutate the store and the ProcessM-compatible query endpoint matches
     * the reference, which never executes DELETE from its API.
     */
    fun executeRead(request: ExecutePqlQueryRequest): QueryResult {
        require(!isDeleteQuery(request.query)) {
            "DELETE is not allowed on a read/query endpoint; use DELETE /logs/{logId}"
        }
        return execute(request)
    }

    /** Cheap textual pre-check for a DELETE query — same guard used by [exportAsXes]. */
    private fun isDeleteQuery(query: String): Boolean =
        query.trim().lowercase().startsWith("delete")

    /**
     * Runs the shared PQL compiler without executing against the backend. Suitable
     * for UI "check my query before I run it" flows.
     *
     * Any [PQLCompileError] along the way is captured into the returned
     * [ValidationResult] as a human-readable error string. Unexpected runtime
     * exceptions propagate because they signal bugs, not user query errors.
     */
    fun validate(request: ValidatePqlQueryRequest): ValidationResult =
        try {
            compiler.prepareForExecution(
                query = request.query,
                logId = request.logId,
                dataStoreId = request.dataStoreId,
            )
            ValidationResult(valid = true, query = request.query)
        } catch (e: PQLCompileError) {
            ValidationResult(
                valid = false,
                query = request.query,
                errors = listOf(e.message ?: e::class.simpleName.orEmpty()),
            )
        }

    /**
     * Runs a PQL query and streams the result straight out as XES XML.
     *
     * Composed from [execute] + [OpenXesWriter]:
     *  - the execute step returns [XesLog] projections already reconstructed,
     *  - the writer serializes them to the caller-provided [OutputStream].
     *
     * Streams into the caller-provided output so export endpoints don't have to
     * materialize the whole XML into memory as a string first.
     * DELETE queries aren't a valid export target — reject early so callers
     * can't silently emit an empty XES file instead of seeing their mistake.
     *
     * Note: this is a self-call into [execute] — proxy-based annotations added
     * to [execute] later (e.g. @Transactional) will not apply on this path.
     */
    fun exportAsXes(request: ExportQueryAsXesRequest, output: OutputStream): ExportResult {
        require(!isDeleteQuery(request.query)) {
            "DELETE queries cannot be exported as XES"
        }

        val result = this.execute(
            ExecutePqlQueryRequest(
                query = request.query,
                logId = request.logId,
                dataStoreId = request.dataStoreId,
                defaultLimits = request.defaultLimits,
                materializedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
            ),
        )
        writer.write(
            logs = result.logs,
            output = output,
            options = XesWriteOptions(compress = request.compress, logName = request.logName),
        )
        return ExportResult(
            logCount = result.logs.size,
            rowCount = result.rowCount,
            executedQueryDescription = result.executedQueryDescription,
        )
    }

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

    private fun QueryExecutionResult.toQueryResult(projection: Projection): QueryResult =
        QueryResult(
            logsProvider = { logs },
            rows = rows,
            rowCount = rowCount,
            executedQueryDescription = executedQueryDescription,
            hasExplicitSelect = projection.hasExplicitSelect(),
            selectAllScopes = projection.selectedAllScopes(),
            projectedLogAttributes = projection.projectedLogAttributes(),
            projectedTraceStandardAttributes = projection.projectedTraceStandardAttributes(),
        )

    private fun applyLogWindow(
        logs: List<XesLog>,
        limit: Long?,
        offset: Long?,
    ): List<XesLog> {
        val afterOffset = logs.drop(offset?.toInt()?.coerceAtLeast(0) ?: 0)
        val effectiveLimit = limit?.toInt()?.takeIf { it >= 0 }
        return effectiveLimit?.let(afterOffset::take) ?: afterOffset
    }

    /**
     * Runs [transform] over the list on a short-lived fixed pool, preserving
     * order. Original exceptions propagate unwrapped, matching sequential
     * `map` semantics. Falls back to plain `map` for zero/one element.
     */
    private fun <T, R> List<T>.mapConcurrently(parallelism: Int, transform: (T) -> R): List<R> {
        if (size <= 1 || parallelism <= 1) return map(transform)
        val pool = Executors.newFixedThreadPool(minOf(size, parallelism))
        try {
            val futures = pool.invokeAll(map { item -> Callable { transform(item) } })
            return futures.map { future ->
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
            }
        } finally {
            pool.shutdown()
        }
    }

    private companion object {
        /** Concurrent per-log query fan-out width for classifier queries spanning many logs. */
        const val PER_LOG_PARALLELISM = 4
    }
}

private fun Projection.hasExplicitSelect(): Boolean =
    !implicitAll && (columns.isNotEmpty() || selectAll.isNotEmpty())

private fun Projection.selectedAllScopes(): Set<Scope> =
    selectAll.filterValues { it }.keys

private fun Projection.projectedLogAttributes(): Set<String> =
    columns.mapNotNull { column ->
        val attribute = column.expression as? PqlExpression.Attribute ?: return@mapNotNull null
        attribute.takeIf { column.scope == Scope.LOG }?.let { it.xesStandardName ?: stripLogScopePrefix(it.name) }
    }.toSet()

private fun stripLogScopePrefix(name: String): String =
    when {
        name.startsWith("l:") -> name.removePrefix("l:")
        name.startsWith("log:") -> name.removePrefix("log:")
        else -> name
    }

private fun Projection.projectedTraceStandardAttributes(): Set<String> =
    columns.mapNotNull { column ->
        val attribute = column.expression as? PqlExpression.Attribute ?: return@mapNotNull null
        attribute.takeIf {
            column.scope == Scope.TRACE &&
                attribute.kind == AttributeKind.STANDARD
        }?.xesStandardName
    }.toSet()
