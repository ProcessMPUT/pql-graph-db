package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import com.processm.processminterpreter.pql.common.HierarchicalOffsets
import com.processm.processminterpreter.pql.plan.CandidateLogPlan
import com.processm.processminterpreter.pql.plan.LogicalPlan
import com.processm.processminterpreter.pql.plan.Projection
import org.springframework.stereotype.Component

/**
 * Facade for rendering logical PQL plans into parameterized Cypher.
 */
@Component
class CypherCodegen(propertyMapper: PhysicalAttributeMapper) {
    private val expressions = CypherExpressionRenderer(propertyMapper)
    private val filterRenderer = CypherFilterRenderer(expressions)
    private val deleteRenderer = CypherDeleteRenderer(filterRenderer)
    private val hierarchyRenderer = CypherHierarchyRenderer(expressions, filterRenderer)
    private val aggregationRenderer = CypherAggregationRenderer(expressions)
    private val projectionRenderer = CypherProjectionRenderer(expressions)
    private val groupByRenderer = CypherGroupByRenderer(expressions)

    fun generate(plan: LogicalPlan): CypherQuery = when (plan) {
        is LogicalPlan.Select -> renderSelect(plan)
        is LogicalPlan.Delete -> deleteRenderer.render(plan)
    }

    fun generate(plan: CandidateLogPlan): CypherQuery = renderCandidateLog(plan)

    /**
     * Renders the minimal probe used before per-log classifier specialization.
     */
    private fun renderCandidateLog(plan: CandidateLogPlan): CypherQuery {
        val shadow = LogicalPlan.Select(
            source = plan.source,
            projection = Projection(columns = emptyList()),
            filter = plan.filter,
            groupBy = null,
            orderBy = plan.orderBy,
            limits = HierarchicalLimits(),
            offsets = HierarchicalOffsets(),
            defaultLimits = HierarchicalLimits(),
            location = plan.location,
        )
        val state = CypherBuildState(shadow)
        CypherMatchEmitter.emit(state)
        filterRenderer.emitWhereClause(state)
        // The probe returns nothing but log ids, so a pending optional event
        // expansion is deliberately dropped rather than emitted: it cannot affect
        // the result and would only multiply rows ahead of the DISTINCT.
        state.discardPendingOptionalEventMatch()
        state.cypher.append(" RETURN DISTINCT log.logId AS logId")
        emitLogOrder(state)
        return state.finish()
    }

    private fun emitLogOrder(state: CypherBuildState) {
        val logOrder = state.plan.orderBy.mapNotNull { key ->
            val scopes = state.facts.scopesOf(key.expression)
            if (scopes.isNotEmpty() && scopes.all { it == Scope.LOG }) {
                "${expressions.render(key.expression, state)} ${key.direction.name}"
            } else {
                null
            }
        }
        val order = (logOrder + "log.logId").distinct()
        state.cypher.append(" ORDER BY ").append(order.joinToString(", "))
    }

    /**
     * Routes SELECT plans to first-class ProcessM result shapes before the generic
     * match/aggregate/project pipeline is used.
     */
    private fun emitPreMatchShapeIfNeeded(s: CypherBuildState): Boolean =
        aggregationRenderer.emitPreMatchIfNeeded(s) ||
            groupByRenderer.emitLimitedBeforeMatchIfNeeded(s)

    private fun emitPostMatchShapeIfNeeded(s: CypherBuildState): Boolean =
        aggregationRenderer.emitPlaceholderHierarchyIfNeeded(s) ||
            groupByRenderer.emitTraceVariantIfNeeded(s) ||
            aggregationRenderer.emitTraceGroupAggregateIfNeeded(s) ||
            groupByRenderer.emitEventGroupByOnlyIfNeeded(s) ||
            aggregationRenderer.emitAggregateOrderByIfNeeded(s)

    /**
     * Renders SELECT plans into parameterized Cypher.
     */
    private fun renderSelect(plan: LogicalPlan.Select): CypherQuery {
        val s = CypherBuildState(plan)
        if (hierarchyRenderer.emitNodeHierarchyIfNeeded(s)) return s.finish()
        if (plan.projection.columns.isNotEmpty() && hierarchyRenderer.emitLimitedHierarchyIfNeeded(s)) {
            projectionRenderer.emitReturnAndOrder(s)
            return s.finish()
        }
        if (emitPreMatchShapeIfNeeded(s)) return s.finish()
        val whereAlreadyEmitted = emitMatchWithEarlyTraceFilterIfNeeded(s)
        if (!whereAlreadyEmitted && !emitWindowedAggregationMatchIfNeeded(s)) {
            CypherMatchEmitter.emit(s)
            filterRenderer.emitWhereClause(s)
            CypherMatchEmitter.emitPendingOptionalEventMatch(s)
        }
        if (emitPostMatchShapeIfNeeded(s)) return s.finish()
        aggregationRenderer.emitIfAggregation(s)
        projectionRenderer.emitReturnAndOrder(s)
        // Hierarchical PQL LIMIT/OFFSET (`limit l:N, t:M, e:K`) cannot be expressed as a
        // single Cypher `LIMIT`: a flat row cap cannot say "K events per trace". The
        // reconstructor applies these per-scope caps against the rebuilt hierarchy.
        return s.finish()
    }

    /**
     * `select min(e:timestamp), ...` (pure event-scope aggregations, no filter,
     * grouping or ordering) has per-trace aggregation semantics, and the
     * hierarchical trace window trims the result rows afterwards — so
     * aggregating every trace of a large log only to discard all but the first
     * N is wasted work (trace-10000: 325ms local vs 33ms in the reference,
     * which windows first). Pre-window the traces by their stable import order
     * when that provably cannot change the surviving rows: a filter would
     * change which traces produce rows, non-event aggregation arguments span
     * scopes the window would truncate, and explicit ordering or offsets need
     * the generic path.
     */
    private fun emitWindowedAggregationMatchIfNeeded(s: CypherBuildState): Boolean {
        if (!canWindowAggregationTraces(s)) return false
        val traceLimit = CypherEffectiveLimits.trace(s) ?: return false
        s.bindHierarchyLimitParams(logLimit = null, traceLimit = traceLimit, eventLimit = null)
        CypherMatchEmitter.emitLog(s)
        s.cypher.append(
            " CALL (log) { MATCH (trace:Trace {parentLogId: log.logId})" +
                " WHERE trace.importOrder IS NOT NULL" +
                " WITH trace ORDER BY trace.parentLogId, trace.importOrder" +
                " LIMIT ${'$'}traceLimit RETURN trace }" +
                " MATCH (trace)-[:HAS_EVENT]->(event:Event)",
        )
        return true
    }

    private fun canWindowAggregationTraces(s: CypherBuildState): Boolean {
        if (s.plan.filter != null || s.plan.orderBy.isNotEmpty()) return false
        if (s.plan.groupBy?.keys?.isNotEmpty() == true) return false
        if (s.plan.projection.selectAll.isNotEmpty()) return false
        if (s.plan.offsets.log != null || s.plan.offsets.trace != null || s.plan.offsets.event != null) return false
        val columns = s.plan.projection.columns
        if (columns.isEmpty()) return false
        if (!columns.all { CypherAggregationInspector.containsAggregation(it.expression) }) return false
        val aggregations = columns.flatMap { CypherAggregationInspector.aggregationsIn(it.expression) }
        return aggregations.isNotEmpty() &&
            aggregations.all { s.facts.scopesOf(it.argument) == setOf(Scope.EVENT) }
    }

    /**
     * Avoid expanding every event in a large log when the WHERE predicate only depends
     * on log/trace data. This preserves the same bindings as the regular full MATCH,
     * but applies the predicate before the expensive trace->event expansion.
     */
    private fun emitMatchWithEarlyTraceFilterIfNeeded(s: CypherBuildState): Boolean {
        val filter = s.plan.filter ?: return false
        if (filterRenderer.classifierNullFilters(s).isNotEmpty()) return false

        if (Scope.EVENT !in s.facts.usedScopes || Scope.TRACE !in s.facts.usedScopes) return false
        if (Scope.EVENT in s.facts.scopesOf(filter)) return false

        CypherMatchEmitter.emitLog(s)
        s.cypher.append("-[:CONTAINS]->(trace:Trace)")
        s.cypher.append(" WHERE ").append(filterRenderer.renderWithHoisting(filter, s))
        s.cypher.append(" MATCH (trace)-[:HAS_EVENT]->(event:Event)")
        return true
    }
}
