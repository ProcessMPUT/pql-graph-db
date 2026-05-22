package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression

internal class CypherNodeHierarchyRenderer(
    private val expressions: CypherExpressionRenderer,
    private val filterRenderer: CypherFilterRenderer,
) {
    private val limitedHierarchyMatcher = CypherLimitedHierarchyMatcher(expressions, filterRenderer)

    fun emitIfNeeded(s: CypherBuildState): Boolean =
        emitSimpleLimitedNodeHierarchyIfNeeded(s) ||
            emitTraceOnlyNodeHierarchyIfNeeded(s) ||
            emitSplitNodeHierarchyIfNeeded(s)

    private fun emitTraceOnlyNodeHierarchyIfNeeded(s: CypherBuildState): Boolean {
        if (!canReturnTraceOnlyNodeHierarchy(s)) return false
        val filter = s.plan.filter ?: return false

        CypherMatchEmitter.emitLog(s)
        s.cypher.append(" RETURN 0 AS _kind, log.logId AS _logKey, 0 AS _traceOrder, properties(log) AS log, null AS trace")
        s.cypher.append(" UNION ALL ")
        CypherMatchEmitter.emitLog(s)
        s.cypher.append("-[:CONTAINS]->(trace:Trace)")
        s.cypher.append(" WHERE ").append(filterRenderer.renderWithHoisting(filter, s))
        s.cypher.append(
            " RETURN 1 AS _kind, log.logId AS _logKey, trace.importOrder AS _traceOrder, null AS log, properties(trace) AS trace",
        )
        return true
    }

    /**
     * Full node-shaped reads should travel as one physical node per row. Repeating the
     * whole log/trace payload on every event row wastes memory, while collecting every
     * event list inside Neo4j creates one large nested response. Split rows let the
     * driver stream the result and let [NodeRowHierarchyBuilder] rebuild the tree.
     */
    private fun emitSplitNodeHierarchyIfNeeded(s: CypherBuildState): Boolean {
        if (!canReturnSplitNodeHierarchy(s)) return false
        if (filterRenderer.classifierNullFilters(s).isNotEmpty()) return false
        val filter = s.plan.filter
        if (filter != null && Scope.EVENT in s.facts.scopesOf(filter)) return false

        s.cypher.append("CALL { ")
        emitFilteredTraceMatch(s, filter)
        s.cypher.append(
            " WITH DISTINCT log" +
                " RETURN 0 AS _kind, log.logId AS _logKey, null AS _traceKey," +
                " 0 AS _traceOrder, 0 AS _eventOrder," +
                " properties(log) AS log, null AS trace, null AS event" +
                " UNION ALL ",
        )
        emitFilteredTraceMatch(s, filter)
        s.cypher.append(
            " RETURN 1 AS _kind, log.logId AS _logKey, trace.traceId AS _traceKey," +
                " trace.importOrder AS _traceOrder, 0 AS _eventOrder," +
                " null AS log, properties(trace) AS trace, null AS event" +
                " UNION ALL ",
        )
        emitFilteredTraceMatch(s, filter)
        s.cypher.append(
            " MATCH (trace)-[:HAS_EVENT]->(event:Event)" +
                " RETURN 2 AS _kind, log.logId AS _logKey, trace.traceId AS _traceKey," +
                " trace.importOrder AS _traceOrder, event.importOrder AS _eventOrder," +
                " null AS log, null AS trace, properties(event) AS event" +
                " }" +
                " RETURN _kind, _logKey, _traceKey, _traceOrder, _eventOrder, log, trace, event" +
                " ORDER BY _kind, _logKey, _traceOrder, _eventOrder",
        )
        return true
    }

    /**
     * For node-shaped hierarchy reads with explicit hierarchical limits, pushing the
     * trace/event caps into Cypher avoids materializing an entire large log only for
     * [HierarchicalWindowing] to trim it afterwards. This matters for Hospital-style
     * logs with huge nested log metadata: returning the log node once per event can
     * exhaust Neo4j heap before the application sees the rows.
     */
    private fun emitSimpleLimitedNodeHierarchyIfNeeded(s: CypherBuildState): Boolean {
        if (s.plan.projection.columns.isNotEmpty()) return false
        if (s.facts.hasAnyAggregation) return false
        if (!limitedHierarchyMatcher.emitIfNeeded(s)) return false
        s.cypher.append(" RETURN log, trace, event ORDER BY ").append(limitedHierarchyMatcher.returnOrder(s))
        return true
    }

    private fun emitFilteredTraceMatch(
        s: CypherBuildState,
        filter: ResolvedExpression?,
    ) {
        CypherMatchEmitter.emitLog(s)
        s.cypher.append("-[:CONTAINS]->(trace:Trace)")
        if (filter != null) {
            s.cypher.append(" WHERE ").append(filterRenderer.renderWithHoisting(filter, s))
        }
    }

    private fun canReturnSplitNodeHierarchy(s: CypherBuildState): Boolean {
        val implicitFullHierarchy = s.plan.projection.implicitAll || s.plan.projection.selectAll.isEmpty()
        val explicitEventHierarchy = s.plan.projection.selectAll[Scope.EVENT] == true
        return s.plan.projection.columns.isEmpty() &&
            (implicitFullHierarchy || explicitEventHierarchy) &&
            Scope.EVENT in s.facts.usedScopes &&
            s.plan.groupBy == null &&
            s.plan.orderBy.isEmpty() &&
            !s.facts.hasAnyAggregation
    }

    private fun canReturnTraceOnlyNodeHierarchy(s: CypherBuildState): Boolean {
        val filter = s.plan.filter ?: return false
        return s.plan.projection.columns.isEmpty() &&
            (s.plan.projection.implicitAll || s.plan.projection.selectAll.isEmpty()) &&
            Scope.EVENT !in s.facts.materializedScopes &&
            s.facts.expressionUsesBaseScope(filter, Scope.TRACE) &&
            !s.facts.expressionUsesBaseScope(filter, Scope.EVENT) &&
            s.plan.groupBy == null &&
            s.plan.orderBy.isEmpty() &&
            s.plan.limits.event == null &&
            s.plan.offsets.event == null &&
            filterRenderer.classifierNullFilters(s).isEmpty() &&
            !s.facts.hasAnyAggregation
    }
}
