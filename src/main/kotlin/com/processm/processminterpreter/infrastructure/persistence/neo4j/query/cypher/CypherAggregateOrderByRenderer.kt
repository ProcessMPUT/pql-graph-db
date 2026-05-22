package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

/**
 * Handles bare node-shaped queries ordered by aggregate expressions.
 */
internal class CypherAggregateOrderByRenderer(
    private val expressions: CypherExpressionRenderer,
) {
    fun emitIfNeeded(s: CypherBuildState): Boolean {
        if (s.plan.projection.columns.isNotEmpty()) return false
        if (s.plan.orderBy.none { CypherAggregationInspector.containsAggregation(it.expression) }) return false

        val orderAliases = s.plan.orderBy.mapIndexed { idx, key ->
            val alias = "_order_$idx"
            "${expressions.render(key.expression, s)} AS $alias"
        }

        s.cypher.append(" WITH ").append((listOf("log") + orderAliases).joinToString(", "))
        s.cypher.append(" MATCH (log)-[:CONTAINS]->(trace:Trace)")
        s.cypher.append(" RETURN log, trace, {} AS event")
        s.cypher.append(" ORDER BY ")
        val orderTerms = buildList {
            addAll(s.plan.orderBy.mapIndexed { idx, key -> "_order_$idx ${key.direction.name}" })
            add("log.name")
            add("trace.importOrder")
        }
        s.cypher.append(orderTerms.joinToString(", "))
        return true
    }
}
