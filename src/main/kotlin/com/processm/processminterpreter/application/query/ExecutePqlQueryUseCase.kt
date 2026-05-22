package com.processm.processminterpreter.application.query

import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.pql.catalog.AttributeKind
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.plan.LogicalPlan
import com.processm.processminterpreter.domain.pql.plan.HierarchicalLimits
import com.processm.processminterpreter.domain.pql.plan.Projection
import com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
import com.processm.processminterpreter.application.ports.DeleteExecutionResult
import com.processm.processminterpreter.application.ports.ExecutionOptions
import com.processm.processminterpreter.application.ports.QueryExecutionResult
import com.processm.processminterpreter.application.ports.QueryPlanExecutor
import org.springframework.stereotype.Component

/**
 * Executes compiled PQL plans against the storage backend.
 *
 * The shared [PqlCompiler] owns parser and domain compilation semantics. This
 * use case owns execution semantics: route a plan to the backend port and return
 * the application result shape expected by controllers and other use cases.
 */
@Component
open class ExecutePqlQueryUseCase(
    private val compiler: PqlCompiler,
    private val executor: QueryPlanExecutor,
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
                val perLogResults = plans.map { plan ->
                    plan to executor.execute(
                        plan,
                        ExecutionOptions(
                            defaultLimits = request.defaultLimits,
                            materializedScopes = request.materializedScopes,
                        ),
                    )
                }
                val mergedLogs = applyLogWindow(
                    logs = perLogResults.flatMap { it.second.logs },
                    limit = prepared.outerLogLimit,
                    offset = prepared.outerLogOffset,
                )
                QueryResult(
                    logs = mergedLogs,
                    rows = perLogResults.flatMap { it.second.rows },
                    rowCount = perLogResults.sumOf { it.second.rowCount },
                    executedQueryDescription = perLogResults.joinToString("\n-- per-log --\n") {
                        it.second.executedQueryDescription
                    },
                    hasExplicitSelect = plans.any { it.projection.hasExplicitSelect() },
                    selectAllScopes = plans.flatMap { it.projection.selectedAllScopes() }.toSet(),
                    projectedTraceStandardAttributes = plans
                        .flatMap { it.projection.projectedTraceStandardAttributes() }
                        .toSet(),
                )
            }
        }
    }

    private fun QueryExecutionResult.toQueryResult(projection: Projection): QueryResult =
        QueryResult(
            logs = logs,
            rows = rows,
            rowCount = rowCount,
            executedQueryDescription = executedQueryDescription,
            hasExplicitSelect = projection.hasExplicitSelect(),
            selectAllScopes = projection.selectedAllScopes(),
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
     * Explicit DELETE entry point for callers that want structured delete metadata
     * rather than a [QueryResult] with an empty logs list.
     */
    fun executeDelete(request: ExecutePqlQueryRequest): DeleteExecutionResult {
        val plan = compiler.compile(
            query = request.query,
            logId = request.logId,
            dataStoreId = request.dataStoreId,
            defaultLimits = request.defaultLimits,
        )
        require(plan is LogicalPlan.Delete) {
            "executeDelete called with a non-DELETE plan: ${plan::class.simpleName}"
        }
        return executor.executeDelete(plan)
    }
}

data class ExecutePqlQueryRequest(
    val query: String,
    val logId: String? = null,
    val dataStoreId: String? = null,
    val defaultLimits: HierarchicalLimits = HierarchicalLimits(),
    val materializedScopes: Set<Scope> = FULL_HIERARCHY_SCOPES,
)

data class QueryResult(
    val logs: List<XesLog>,
    val rows: List<Map<String, Any?>> = emptyList(),
    val rowCount: Int = 0,
    val executedQueryDescription: String = "",
    val hasExplicitSelect: Boolean = false,
    val selectAllScopes: Set<Scope> = emptySet(),
    val projectedTraceStandardAttributes: Set<String> = emptySet(),
)

private fun Projection.hasExplicitSelect(): Boolean =
    !implicitAll && (columns.isNotEmpty() || selectAll.isNotEmpty())

private fun Projection.selectedAllScopes(): Set<Scope> =
    selectAll.filterValues { it }.keys

private fun Projection.projectedTraceStandardAttributes(): Set<String> =
    columns.mapNotNull { column ->
        val attribute = column.expression as? ResolvedAttribute ?: return@mapNotNull null
        attribute.takeIf {
            column.scope == Scope.TRACE &&
                attribute.kind == AttributeKind.STANDARD
        }?.xesStandardName
    }.toSet()

private val FULL_HIERARCHY_SCOPES: Set<Scope> =
    setOf(Scope.LOG, Scope.TRACE, Scope.EVENT)
