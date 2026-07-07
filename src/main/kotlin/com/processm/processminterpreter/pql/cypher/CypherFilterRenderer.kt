package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.catalog.AttributeKind
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.ast.PqlExpression

internal class CypherFilterRenderer(
    private val expressions: CypherExpressionRenderer,
) {
    fun emitWhereClause(s: CypherBuildState) {
        val filter = s.plan.filter
        val classifierNullFilters = classifierNullFilters(s)
        if (filter == null && classifierNullFilters.isEmpty()) return
        s.cypher.append(" WHERE ")
        val parts = buildList {
            if (filter != null) add(renderWithHoisting(filter, s))
            addAll(classifierNullFilters)
        }
        s.cypher.append(parts.joinToString(" AND "))
    }

    /**
     * Render the WHERE filter, transparently rewriting hoisted-event references as an
     * `EXISTS { ... }` subquery when the outer MATCH doesn't already bind `event`.
     *
     * Example: `FROM trace WHERE ^e:name = 'X'` needs
     * `EXISTS { MATCH (trace)-[:HAS_EVENT]->(_hev:Event) WHERE _hev.activity = $p }`
     * because the outer scope has no event variable to reference.
     */
    fun renderWithHoisting(filter: PqlExpression, s: CypherBuildState): String {
        val hoistScope = hoistedEventEffectiveScope(filter) ?: return expressions.render(filter, s)

        val eventAlias = "_hev"
        val inner = expressions.renderWithHoistedEventNode(filter, s, eventAlias)
        val match = hoistedEventMatch(hoistScope, eventAlias)
        return "EXISTS { $match WHERE $inner }"
    }

    /**
     * Classifier attributes referenced in ORDER BY (or GROUP BY) can refer to non-existent
     * classifier keys. ProcessM throws on unknown classifiers; we instead emit `attr IS NOT
     * NULL` so unknown classifiers produce an empty result set (see MEMORY.md classifier
     * ORDER BY filter). We only emit the guard once per distinct property reference.
     */
    fun classifierNullFilters(s: CypherBuildState): List<String> {
        val seen = linkedSetOf<String>()
        s.plan.orderBy.forEach { collectClassifierNullFilters(it.expression, seen) }
        s.plan.groupBy?.keys?.forEach { collectClassifierNullFilters(it, seen) }
        return seen.toList()
    }

    private fun hoistedEventEffectiveScope(e: PqlExpression): Scope? = when (e) {
        is PqlExpression.Attribute ->
            if (e.baseScope == Scope.EVENT && e.effectiveScope != Scope.EVENT) e.effectiveScope else null
        is PqlExpression.Binary -> widestHoistScope(listOfNotNull(
            hoistedEventEffectiveScope(e.left),
            hoistedEventEffectiveScope(e.right),
        ))
        is PqlExpression.Unary -> hoistedEventEffectiveScope(e.operand)
        is PqlExpression.Call -> widestHoistScope(e.arguments.mapNotNull(::hoistedEventEffectiveScope))
        is PqlExpression.InList -> widestHoistScope(e.values.mapNotNull(::hoistedEventEffectiveScope))
        is PqlExpression.Aggregation -> hoistedEventEffectiveScope(e.argument)
        is PqlExpression.Literal, is PqlExpression.AttributeRef -> null
    }

    private fun collectClassifierNullFilters(
        expression: PqlExpression,
        filters: MutableSet<String>,
    ) {
        when (expression) {
            is PqlExpression.Attribute -> if (expression.kind == AttributeKind.CLASSIFIER) {
                expressions.classifierKeyAttributes(expression).forEach { key ->
                    filters.add("${expressions.propertyRef(key).toCypher()} IS NOT NULL")
                }
            }
            is PqlExpression.Binary -> {
                collectClassifierNullFilters(expression.left, filters)
                collectClassifierNullFilters(expression.right, filters)
            }
            is PqlExpression.Unary -> collectClassifierNullFilters(expression.operand, filters)
            is PqlExpression.Call -> expression.arguments.forEach { collectClassifierNullFilters(it, filters) }
            is PqlExpression.InList -> expression.values.forEach { collectClassifierNullFilters(it, filters) }
            is PqlExpression.Aggregation -> collectClassifierNullFilters(expression.argument, filters)
            is PqlExpression.Literal, is PqlExpression.AttributeRef -> Unit
        }
    }

    private fun hoistedEventMatch(scope: Scope, eventAlias: String): String = when (scope) {
        Scope.LOG -> "MATCH (log)-[:CONTAINS]->(:Trace)-[:HAS_EVENT]->($eventAlias:Event)"
        Scope.TRACE -> "MATCH (trace)-[:HAS_EVENT]->($eventAlias:Event)"
        Scope.EVENT -> error("Event-scope references are not hoisted")
    }

    private fun widestHoistScope(scopes: Collection<Scope>): Scope? = when {
        Scope.LOG in scopes -> Scope.LOG
        Scope.TRACE in scopes -> Scope.TRACE
        else -> null
    }
}
