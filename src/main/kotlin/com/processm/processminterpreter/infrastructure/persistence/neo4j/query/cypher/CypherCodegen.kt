package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.plan.LogicalPlan
import com.processm.processminterpreter.domain.pql.plan.CandidateLogPlan

/**
 * Facade for rendering logical PQL plans into parameterized Cypher.
 */
class CypherCodegen(propertyMapper: PhysicalAttributeMapper) {
    private val expressions = CypherExpressionRenderer(propertyMapper)
    private val filterRenderer = CypherFilterRenderer(expressions)
    private val selectRenderer = CypherSelectRenderer(expressions, filterRenderer)
    private val deleteRenderer = CypherDeleteRenderer(filterRenderer)
    private val candidateLogRenderer = CypherCandidateLogRenderer(expressions, filterRenderer)

    fun generate(plan: LogicalPlan): CypherQuery = when (plan) {
        is LogicalPlan.Select -> selectRenderer.render(plan)
        is LogicalPlan.Delete -> deleteRenderer.render(plan)
    }

    fun generate(plan: CandidateLogPlan): CypherQuery = candidateLogRenderer.render(plan)
}
