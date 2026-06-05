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
    fun emitSplitRowsIfNeeded(s: CypherBuildState): Boolean {
        if (!canEmit(s)) return false
        if (s.plan.orderBy.isNotEmpty()) return false

        val filter = s.plan.filter
        val filterScopes = filter?.let(s.facts::scopesOf).orEmpty()
        val logLimit = CypherEffectiveLimits.log(s).takeIf { filterScopes.all { scope -> scope == Scope.LOG } }
        val traceLimit = CypherEffectiveLimits.trace(s)
        val eventLimit = eventLimitForInputExpansion(s)

        s.bindHierarchyLimitParams(logLimit, traceLimit, eventLimit)

        s.cypher.append("CALL { ")
        emitLimitedLogRows(s, filter, filterScopes, logLimit)
        s.cypher.append(" UNION ALL ")
        emitLimitedTraceRows(s, filter, filterScopes, logLimit, traceLimit)
        s.cypher.append(" UNION ALL ")
        emitLimitedEventRows(s, filter, filterScopes, logLimit, traceLimit, eventLimit)
        s.cypher.append(
            " } RETURN _kind, _logKey, _traceKey, _traceOrder, _eventOrder, log, trace, event" +
                " ORDER BY _kind, _logKey, _traceOrder, _eventOrder",
        )
        return true
    }

    fun emitIfNeeded(s: CypherBuildState): Boolean {
        if (!canEmit(s)) return false

        val filter = s.plan.filter
        val filterScopes = filter?.let(s.facts::scopesOf).orEmpty()
        val logLimit = CypherEffectiveLimits.log(s).takeIf { filterScopes.all { scope -> scope == Scope.LOG } }
        val traceLimit = CypherEffectiveLimits.trace(s)
        val eventLimit = eventLimitForInputExpansion(s)

        s.bindHierarchyLimitParams(logLimit, traceLimit, eventLimit)

        CypherMatchEmitter.emitLog(s)
        if (filter != null && filterScopes.all { it == Scope.LOG }) {
            s.cypher.append(" WHERE ").append(filterRenderer.renderWithHoisting(filter, s))
        }
        if (logLimit != null) {
            emitLogLimit(s)
        }

        s.cypher.append(" CALL (log) { MATCH (log)-[:CONTAINS]->(trace:Trace)")
        if (Scope.EVENT in filterScopes) {
            emitEventFilteredExpansion(s, filter!!, traceLimit, eventLimit)
        } else {
            emitRegularExpansion(s, filter, filterScopes, traceLimit, eventLimit)
        }
        return true
    }

    private fun emitLimitedLogRows(
        s: CypherBuildState,
        filter: com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression?,
        filterScopes: Set<Scope>,
        logLimit: Long?,
    ) {
        emitLimitedLogMatch(s, filter, filterScopes, logLimit)
        if (filter != null && filterScopes.any { it != Scope.LOG }) {
            if (Scope.EVENT in filterScopes) {
                s.cypher.append(" MATCH (log)-[:CONTAINS]->(trace:Trace)-[:HAS_EVENT]->(event:Event)")
            } else {
                s.cypher.append(" MATCH (log)-[:CONTAINS]->(trace:Trace)")
            }
            s.cypher.append(" WHERE ").append(filterRenderer.renderWithHoisting(filter, s))
            s.cypher.append(" WITH DISTINCT log")
        }
        s.cypher.append(
            " RETURN 0 AS _kind, log.logId AS _logKey, null AS _traceKey," +
                " 0 AS _traceOrder, 0 AS _eventOrder," +
                " properties(log) AS log, null AS trace, null AS event",
        )
    }

    private fun emitLimitedTraceRows(
        s: CypherBuildState,
        filter: com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression?,
        filterScopes: Set<Scope>,
        logLimit: Long?,
        traceLimit: Long?,
    ) {
        emitLimitedLogMatch(s, filter, filterScopes, logLimit)
        emitLimitedTraceSubquery(s, filter, filterScopes, traceLimit, eventLimit = null, returnEvents = false)
        s.cypher.append(
            " RETURN 1 AS _kind, log.logId AS _logKey, trace.traceId AS _traceKey," +
                " trace.importOrder AS _traceOrder, 0 AS _eventOrder," +
                " null AS log, properties(trace) AS trace, null AS event",
        )
    }

    private fun emitLimitedEventRows(
        s: CypherBuildState,
        filter: com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression?,
        filterScopes: Set<Scope>,
        logLimit: Long?,
        traceLimit: Long?,
        eventLimit: Long?,
    ) {
        emitLimitedLogMatch(s, filter, filterScopes, logLimit)
        emitLimitedTraceSubquery(s, filter, filterScopes, traceLimit, eventLimit, returnEvents = true)
        s.cypher.append(
            " RETURN 2 AS _kind, log.logId AS _logKey, trace.traceId AS _traceKey," +
                " trace.importOrder AS _traceOrder, event.importOrder AS _eventOrder," +
                " null AS log, null AS trace, properties(event) AS event",
        )
    }

    private fun emitLimitedLogMatch(
        s: CypherBuildState,
        filter: com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression?,
        filterScopes: Set<Scope>,
        logLimit: Long?,
    ) {
        CypherMatchEmitter.emitLog(s)
        if (filter != null && filterScopes.all { it == Scope.LOG }) {
            s.cypher.append(" WHERE ").append(filterRenderer.renderWithHoisting(filter, s))
        }
        if (logLimit != null) {
            emitLogLimit(s)
        }
    }

    private fun emitLimitedTraceSubquery(
        s: CypherBuildState,
        filter: com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression?,
        filterScopes: Set<Scope>,
        traceLimit: Long?,
        eventLimit: Long?,
        returnEvents: Boolean,
    ) {
        s.cypher.append(" CALL (log) { MATCH (log)-[:CONTAINS]->(trace:Trace)")
        if (Scope.EVENT in filterScopes) {
            emitEventFilteredTraceSubquery(s, filter!!, traceLimit, eventLimit, returnEvents)
        } else {
            emitRegularTraceSubquery(s, filter, filterScopes, traceLimit, eventLimit, returnEvents)
        }
    }

    private fun emitRegularTraceSubquery(
        s: CypherBuildState,
        filter: com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression?,
        filterScopes: Set<Scope>,
        traceLimit: Long?,
        eventLimit: Long?,
        returnEvents: Boolean,
    ) {
        if (filter != null && filterScopes.any { it != Scope.LOG }) {
            s.cypher.append(" WHERE ").append(filterRenderer.renderWithHoisting(filter, s))
        }
        s.cypher.append(" WITH trace ORDER BY ${traceOrder(s)}")
        traceLimit?.let { s.cypher.append(" LIMIT ${'$'}traceLimit") }
        if (!returnEvents) {
            s.cypher.append(" RETURN trace }")
            return
        }
        s.cypher.append(" CALL (trace) { MATCH (trace)-[:HAS_EVENT]->(event:Event)")
        s.cypher.append(" WITH event ORDER BY ${eventOrder(s)}")
        eventLimit?.let { s.cypher.append(" LIMIT ${'$'}eventLimit") }
        s.cypher.append(" RETURN event } RETURN trace, event }")
    }

    private fun emitEventFilteredTraceSubquery(
        s: CypherBuildState,
        filter: com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression,
        traceLimit: Long?,
        eventLimit: Long?,
        returnEvents: Boolean,
    ) {
        s.cypher.append(" CALL (log, trace) { MATCH (trace)-[:HAS_EVENT]->(event:Event)")
        s.cypher.append(" WHERE ").append(filterRenderer.renderWithHoisting(filter, s))
        if (!returnEvents) {
            s.cypher.append(" RETURN count(event) > 0 AS _hasEvents }")
            s.cypher.append(" WITH trace WHERE _hasEvents")
            s.cypher.append(" WITH trace ORDER BY ${traceOrder(s)}")
            traceLimit?.let { s.cypher.append(" LIMIT ${'$'}traceLimit") }
            s.cypher.append(" RETURN trace }")
            return
        }
        s.cypher.append(" WITH event ORDER BY ${eventOrder(s)}")
        eventLimit?.let { s.cypher.append(" LIMIT ${'$'}eventLimit") }
        s.cypher.append(" RETURN collect(event) AS _events }")
        s.cypher.append(" WITH trace, _events WHERE size(_events) > 0")
        s.cypher.append(" WITH trace, _events ORDER BY ${traceOrder(s)}")
        traceLimit?.let { s.cypher.append(" LIMIT ${'$'}traceLimit") }
        s.cypher.append(" UNWIND _events AS event RETURN trace, event }")
    }

    private fun canEmit(s: CypherBuildState): Boolean {
        if (Scope.EVENT !in s.facts.usedScopes) return false
        if (s.plan.groupBy?.keys?.isNotEmpty() == true) return false
        if (s.facts.hasAnyAggregation && s.plan.projection.selectAll[Scope.TRACE] != true) return false
        if (!canPushOrder(s)) return false
        if (s.plan.offsets.log != null || s.plan.offsets.trace != null || s.plan.offsets.event != null) return false
        return listOfNotNull(
            CypherEffectiveLimits.log(s),
            CypherEffectiveLimits.trace(s),
            CypherEffectiveLimits.event(s),
        ).isNotEmpty()
    }

    private fun eventLimitForInputExpansion(s: CypherBuildState): Long? =
        if (s.facts.hasAnyAggregation) null else CypherEffectiveLimits.event(s)

    private fun canPushOrder(s: CypherBuildState): Boolean =
        s.plan.orderBy.all { key ->
            !CypherAggregationInspector.containsAggregation(key.expression) &&
                s.facts.scopesOf(key.expression).size <= 1
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
        s.cypher.append(" WITH trace ORDER BY ${traceOrder(s)}")
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
        s.cypher.append(" WITH trace, _events ORDER BY ${traceOrder(s)}")
        traceLimit?.let { s.cypher.append(" LIMIT ${'$'}traceLimit") }
        s.cypher.append(" UNWIND _events AS event RETURN trace, event }")
    }

    fun returnOrder(s: CypherBuildState): String =
        listOf(
            logOrder(s),
            traceOrder(s),
            eventOrder(s),
        ).joinToString(", ")

    private fun eventOrder(s: CypherBuildState): String =
        scopedOrder(s, Scope.EVENT, "event.importOrder")

    private fun traceOrder(s: CypherBuildState): String =
        scopedOrder(s, Scope.TRACE, "trace.importOrder")

    private fun logOrder(s: CypherBuildState): String =
        scopedOrder(s, Scope.LOG, defaultLogOrder(s))

    private fun emitLogLimit(s: CypherBuildState) {
        if (!requiresChildOrderForLogLimit(s)) {
            s.cypher.append(" WITH log ORDER BY ${logOrder(s)} LIMIT ${'$'}logLimit")
            return
        }

        val aliases = s.plan.orderBy.mapIndexed { index, _ -> "_logOrder$index" }
        val usesEventOrder = s.plan.orderBy.any { key -> Scope.EVENT in s.facts.scopesOf(key.expression) }
        val match = if (usesEventOrder) {
            "MATCH (log)-[:CONTAINS]->(trace:Trace)-[:HAS_EVENT]->(event:Event)"
        } else {
            "MATCH (log)-[:CONTAINS]->(trace:Trace)"
        }
        val returnTerms = s.plan.orderBy.mapIndexed { index, key ->
            "${expressions.render(key.expression, s)} AS ${aliases[index]}"
        }

        s.cypher.append(" CALL (log) { ")
        s.cypher.append(match)
        s.cypher.append(" WITH trace")
        if (usesEventOrder) s.cypher.append(", event")
        s.cypher.append(" ORDER BY ").append(logSelectionOrder(s, usesEventOrder))
        s.cypher.append(" LIMIT 1 RETURN ").append(returnTerms.joinToString(", "))
        s.cypher.append(" } WITH log")
        aliases.forEach { alias -> s.cypher.append(", $alias") }
        s.cypher.append(" ORDER BY ")
        s.cypher.append(
            s.plan.orderBy.mapIndexed { index, key -> "${aliases[index]} ${key.direction.name}" }
                .plus(defaultLogOrder(s))
                .joinToString(", "),
        )
        s.cypher.append(" LIMIT ${'$'}logLimit")
    }

    private fun requiresChildOrderForLogLimit(s: CypherBuildState): Boolean =
        s.plan.orderBy.any { key ->
            val scopes = s.facts.scopesOf(key.expression)
            Scope.TRACE in scopes || Scope.EVENT in scopes
        }

    private fun logSelectionOrder(
        s: CypherBuildState,
        usesEventOrder: Boolean,
    ): String {
        val explicit = s.plan.orderBy.map { key ->
            "${expressions.render(key.expression, s)} ${key.direction.name}"
        }
        val fallback = if (usesEventOrder) {
            listOf("trace.importOrder", "event.importOrder")
        } else {
            listOf("trace.importOrder")
        }
        return (explicit + fallback).distinct().joinToString(", ")
    }

    private fun scopedOrder(
        s: CypherBuildState,
        scope: Scope,
        defaultTerm: String,
    ): String {
        val terms = s.plan.orderBy.mapNotNull { key ->
            val scopes = s.facts.scopesOf(key.expression)
            if (scopes.singleOrNull() != scope) return@mapNotNull null
            "${expressions.render(key.expression, s)} ${key.direction.name}"
        }
        return (terms + defaultTerm).distinct().joinToString(", ")
    }

    private fun defaultLogOrder(s: CypherBuildState): String =
        if (s.plan.source.dataStoreId != null) {
            "log.createdAt, log.logId"
        } else {
            "log.logId"
        }
}
