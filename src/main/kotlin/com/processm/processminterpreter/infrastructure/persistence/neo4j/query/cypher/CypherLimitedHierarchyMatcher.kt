package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope

/**
 * Emits a bounded `log -> trace -> event` binding for simple hierarchy reads.
 *
 * Generic Cypher rendering must materialize every matching row and apply PQL's
 * per-scope windowing in Kotlin later. For simple no-group shapes, including
 * event-only ordering, we can safely push the same hierarchy limits into nested
 * Cypher subqueries before the row shape is rendered. This keeps both node-shaped
 * and projected reads from expanding an entire log only to discard most of it afterwards.
 */
internal class CypherLimitedHierarchyMatcher(
    private val expressions: CypherExpressionRenderer,
    private val filterRenderer: CypherFilterRenderer,
) {
    fun emitIfNeeded(s: CypherBuildState): Boolean {
        if (!canEmit(s)) return false

        val filter = s.plan.filter
        val filterScopes = filter?.let(s.facts::scopesOf).orEmpty()
        val logLimit = CypherEffectiveLimits.log(s).takeIf { filterScopes.all { scope -> scope == Scope.LOG } }
        val traceLimit = CypherEffectiveLimits.trace(s)
        val eventLimit = CypherEffectiveLimits.event(s)

        s.bindHierarchyLimitParams(logLimit, traceLimit, eventLimit)

        CypherMatchEmitter.emitLog(s)
        if (filter != null && filterScopes.all { it == Scope.LOG }) {
            s.cypher.append(" WHERE ").append(filterRenderer.renderWithHoisting(filter, s))
        }
        if (logLimit != null) {
            s.cypher.append(" WITH log ORDER BY log.logId LIMIT ${'$'}logLimit")
        }

        s.cypher.append(" CALL (log) { MATCH (log)-[:CONTAINS]->(trace:Trace)")
        if (Scope.EVENT in filterScopes) {
            emitEventFilteredExpansion(s, filter!!, traceLimit, eventLimit)
        } else {
            emitRegularExpansion(s, filter, filterScopes, traceLimit, eventLimit)
        }
        return true
    }

    private fun canEmit(s: CypherBuildState): Boolean {
        if (Scope.TRACE !in s.facts.usedScopes || Scope.EVENT !in s.facts.usedScopes) return false
        if (s.plan.groupBy != null) return false
        if (!canPushOrder(s)) return false
        if (s.plan.offsets.log != null || s.plan.offsets.trace != null || s.plan.offsets.event != null) return false
        return listOfNotNull(
            CypherEffectiveLimits.log(s),
            CypherEffectiveLimits.trace(s),
            CypherEffectiveLimits.event(s),
        ).isNotEmpty()
    }

    private fun canPushOrder(s: CypherBuildState): Boolean =
        s.plan.orderBy.all { key ->
            !CypherAggregationInspector.containsAggregation(key.expression) &&
                s.facts.scopesOf(key.expression).all { it == Scope.EVENT }
        }

    private fun emitRegularExpansion(
        s: CypherBuildState,
        filter: com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression?,
        filterScopes: Set<Scope>,
        traceLimit: Long?,
        eventLimit: Long?,
    ) {
        if (filter != null && filterScopes.any { it != Scope.LOG }) {
            s.cypher.append(" WHERE ").append(filterRenderer.renderWithHoisting(filter, s))
        }
        s.cypher.append(" WITH trace ORDER BY trace.importOrder")
        traceLimit?.let { s.cypher.append(" LIMIT ${'$'}traceLimit") }

        if (eventLimit == null) {
            s.cypher.append(" MATCH (trace)-[:HAS_EVENT]->(event:Event)")
            if (s.plan.orderBy.isNotEmpty()) {
                s.cypher.append(" WITH trace, event ORDER BY ").append(eventOrder(s))
            }
            s.cypher.append(" RETURN trace, event }")
        } else {
            s.cypher.append(
                " CALL (trace) { MATCH (trace)-[:HAS_EVENT]->(event:Event)" +
                    " WITH event ORDER BY ${eventOrder(s)} LIMIT ${'$'}eventLimit" +
                    " RETURN collect(event) AS _events }" +
                    " UNWIND _events AS event RETURN trace, event }",
            )
        }
    }

    private fun emitEventFilteredExpansion(
        s: CypherBuildState,
        filter: com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression,
        traceLimit: Long?,
        eventLimit: Long?,
    ) {
        s.cypher.append(
            " CALL (log, trace) {" +
                " MATCH (trace)-[:HAS_EVENT]->(event:Event)" +
                " WHERE ${filterRenderer.renderWithHoisting(filter, s)}" +
                " WITH event ORDER BY ${eventOrder(s)}",
        )
        eventLimit?.let { s.cypher.append(" LIMIT ${'$'}eventLimit") }
        s.cypher.append(" RETURN collect(event) AS _events }")
        s.cypher.append(" WITH trace, _events WHERE size(_events) > 0")
        s.cypher.append(" WITH trace, _events ORDER BY trace.importOrder")
        traceLimit?.let { s.cypher.append(" LIMIT ${'$'}traceLimit") }
        s.cypher.append(" UNWIND _events AS event RETURN trace, event }")
    }

    fun returnOrder(s: CypherBuildState): String =
        listOf(
            "log.logId",
            "trace.importOrder",
            eventOrder(s),
        ).joinToString(", ")

    private fun eventOrder(s: CypherBuildState): String =
        if (s.plan.orderBy.isEmpty()) {
            "event.importOrder"
        } else {
            s.plan.orderBy.joinToString(", ") { key ->
                "${expressions.render(key.expression, s)} ${key.direction.name}"
            }
        }
}
