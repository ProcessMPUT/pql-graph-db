package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.plan.LogicalPlan

/**
 * Renders SELECT plans into parameterized Cypher.
 */
internal class CypherSelectRenderer(
    private val expressions: CypherExpressionRenderer,
    private val filterRenderer: CypherFilterRenderer,
) {
    private val nodeHierarchyRenderer = CypherNodeHierarchyRenderer(expressions, filterRenderer)
    private val limitedHierarchyMatcher = CypherLimitedHierarchyMatcher(expressions, filterRenderer)
    private val shapeRouter = CypherSelectShapeRouter(expressions)
    private val aggregationWithRenderer = CypherAggregationWithRenderer(expressions)
    private val projectionRenderer = CypherProjectionRenderer(expressions)

    fun render(plan: LogicalPlan.Select): CypherQuery {
        val s = CypherBuildState(plan)
        if (nodeHierarchyRenderer.emitIfNeeded(s)) return s.finish()
        if (plan.projection.columns.isNotEmpty() && limitedHierarchyMatcher.emitIfNeeded(s)) {
            projectionRenderer.emitReturnAndOrder(s)
            return s.finish()
        }
        if (shapeRouter.emitPreMatchShapeIfNeeded(s)) return s.finish()
        val whereAlreadyEmitted = emitMatchWithEarlyTraceFilterIfNeeded(s)
        if (!whereAlreadyEmitted) {
            CypherMatchEmitter.emit(s)
            filterRenderer.emitWhereClause(s)
        }
        if (shapeRouter.emitPostMatchShapeIfNeeded(s)) return s.finish()
        aggregationWithRenderer.emitIfAggregation(s)
        projectionRenderer.emitReturnAndOrder(s)
        // Hierarchical PQL LIMIT/OFFSET (`limit l:N, t:M, e:K`) cannot be expressed as a
        // single Cypher `LIMIT`: a flat row cap cannot say "K events per trace". The
        // reconstructor applies these per-scope caps against the rebuilt hierarchy.
        return s.finish()
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
