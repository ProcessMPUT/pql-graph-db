package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope

/**
 * Handles bare node-shaped queries ordered by aggregate expressions.
 */
internal class CypherAggregateOrderByRenderer(
    private val expressions: CypherExpressionRenderer,
) {
    fun emitPreMatchIfNeeded(s: CypherBuildState): Boolean {
        if (!canPreAggregateByLog(s)) return false

        CypherMatchEmitter.emitLog(s)
        s.cypher.append(" CALL (log) {")
        s.cypher.append(" MATCH (log)-[:CONTAINS]->(trace:Trace)-[:HAS_EVENT]->(event:Event)")
        s.cypher.append(" RETURN ").append(orderAliases(s).joinToString(", "))
        s.cypher.append(" }")
        emitSplitPlaceholderRows(s)
        return true
    }

    fun emitIfNeeded(s: CypherBuildState): Boolean {
        if (s.plan.projection.columns.isNotEmpty()) return false
        if (s.plan.orderBy.none { CypherAggregationInspector.containsAggregation(it.expression) }) return false

        s.cypher.append(" WITH ").append((listOf("log") + orderAliases(s)).joinToString(", "))
        s.cypher.append(" MATCH (log)-[:CONTAINS]->(trace:Trace)")
        s.cypher.append(" RETURN log, trace, {} AS event")
        appendOrderBy(s)
        return true
    }

    private fun canPreAggregateByLog(s: CypherBuildState): Boolean {
        if (s.plan.projection.columns.isNotEmpty()) return false
        if (s.plan.filter != null) return false
        if (s.plan.groupBy?.keys?.isNotEmpty() == true) return false
        if (s.plan.orderBy.isEmpty()) return false
        if (s.plan.orderBy.any { !CypherAggregationInspector.containsAggregation(it.expression) }) return false
        return s.plan.orderBy
            .flatMap { CypherAggregationInspector.aggregationsIn(it.expression) }
            .all { Scope.EVENT in s.facts.scopesOf(it.argument) }
    }

    private fun orderAliases(s: CypherBuildState): List<String> =
        s.plan.orderBy.mapIndexed { idx, key ->
            val alias = "_order_$idx"
            "${expressions.render(key.expression, s)} AS $alias"
        }

    private fun emitSplitPlaceholderRows(s: CypherBuildState) {
        val orderAliasNames = s.plan.orderBy.indices.map { "_order_$it" }
        val passThrough = (listOf("log") + orderAliasNames).joinToString(", ")

        s.cypher.append(" CALL {")
        s.cypher.append(" WITH $passThrough")
        s.cypher.append(
            " RETURN 0 AS _kind, log.logId AS _logKey, null AS _traceKey," +
                " 0 AS _traceOrder, log.name AS _logName," +
                " properties(log) AS _log_node_, null AS _trace_node_, null AS _event_node_",
        )
        s.cypher.append(" UNION ALL")
        s.cypher.append(" WITH $passThrough")
        s.cypher.append(" MATCH (log)-[:CONTAINS]->(trace:Trace)")
        s.cypher.append(
            " RETURN 1 AS _kind, log.logId AS _logKey, trace.traceId AS _traceKey," +
                " trace.importOrder AS _traceOrder, log.name AS _logName," +
                " null AS _log_node_, properties(trace) AS _trace_node_, {} AS _event_node_",
        )
        s.cypher.append(" }")
        s.cypher.append(
            " RETURN _kind, _logKey, _traceKey, _traceOrder," +
                " _log_node_ AS log, _trace_node_ AS trace, _event_node_ AS event" +
                orderAliasNames.joinToString("") { ", $it" },
        )
        appendOrderBy(s, logNameColumn = "_logName", traceOrderColumn = "_traceOrder")
    }

    private fun appendOrderBy(s: CypherBuildState) {
        appendOrderBy(s, logNameColumn = "log.name", traceOrderColumn = "trace.importOrder")
    }

    private fun appendOrderBy(
        s: CypherBuildState,
        logNameColumn: String,
        traceOrderColumn: String,
    ) {
        s.cypher.append(" ORDER BY ")
        val orderTerms = buildList {
            addAll(s.plan.orderBy.mapIndexed { idx, key -> "_order_$idx ${key.direction.name}" })
            add(logNameColumn)
            add(traceOrderColumn)
        }
        s.cypher.append(orderTerms.joinToString(", "))
    }
}
