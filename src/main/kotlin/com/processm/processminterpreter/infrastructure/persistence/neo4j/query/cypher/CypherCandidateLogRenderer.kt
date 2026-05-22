package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.plan.CandidateLogPlan
import com.processm.processminterpreter.domain.pql.plan.HierarchicalLimits
import com.processm.processminterpreter.domain.pql.plan.HierarchicalOffsets
import com.processm.processminterpreter.domain.pql.plan.LogicalPlan
import com.processm.processminterpreter.domain.pql.plan.Projection

/**
 * Renders the minimal probe used before per-log classifier specialization.
 */
internal class CypherCandidateLogRenderer(
    private val expressions: CypherExpressionRenderer,
    private val filterRenderer: CypherFilterRenderer,
) {
    fun render(plan: CandidateLogPlan): CypherQuery {
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
}
