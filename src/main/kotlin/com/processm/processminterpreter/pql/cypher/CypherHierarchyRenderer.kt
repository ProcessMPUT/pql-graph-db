package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.ast.PqlExpression

internal class CypherHierarchyRenderer(
    private val expressions: CypherExpressionRenderer,
    private val filterRenderer: CypherFilterRenderer,
) {
    fun emitNodeHierarchyIfNeeded(s: CypherBuildState): Boolean =
        emitSimpleLimitedNodeHierarchyIfNeeded(s) ||
            emitTraceOnlyNodeHierarchyIfNeeded(s) ||
            emitSplitNodeHierarchyIfNeeded(s)

    private fun emitTraceOnlyNodeHierarchyIfNeeded(s: CypherBuildState): Boolean {
        if (!canReturnTraceOnlyNodeHierarchy(s)) return false
        val filter = s.plan.filter ?: return false

        emitFilteredTraceMatch(s, filter)
        s.cypher.append(" WITH DISTINCT log")
        s.cypher.append(" RETURN 0 AS _kind, log.logId AS _logKey, 0 AS _traceOrder, properties(log) AS log, null AS trace")
        s.cypher.append(" UNION ALL ")
        emitFilteredTraceMatch(s, filter)
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
        if (emitSplitRowsIfNeeded(s)) return true
        if (!emitLimitedHierarchyIfNeeded(s)) return false
        s.deferLogProperties()
        s.cypher.append(" RETURN log.logId AS $SYNTHETIC_LOG_KEY_ALIAS, trace, event ORDER BY ")
            .append(returnOrder(s))
        return true
    }

    private fun emitFilteredTraceMatch(
        s: CypherBuildState,
        filter: PqlExpression?,
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

    private fun emitSplitRowsIfNeeded(s: CypherBuildState): Boolean {
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

    /**
     * Emits a bounded `log -> trace -> event` binding for simple hierarchy reads.
     *
     * Generic Cypher rendering must materialize every matching row and apply PQL's
     * per-scope windowing in Kotlin later. For simple no-group shapes, including
     * event-only ordering, we can safely push the same hierarchy limits into nested
     * Cypher subqueries before the row shape is rendered. This keeps both node-shaped
     * and projected reads from expanding an entire log only to discard most of it afterwards.
     */
    fun emitLimitedHierarchyIfNeeded(s: CypherBuildState): Boolean {
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

        s.cypher.append(" CALL (log) {")
        emitIndexedTraceMatch(s)
        if (Scope.EVENT in filterScopes) {
            emitEventFilteredExpansion(s, filter!!, traceLimit, eventLimit)
        } else {
            emitRegularExpansion(s, filter, filterScopes, traceLimit, eventLimit)
        }
        return true
    }

    private fun emitLimitedLogRows(
        s: CypherBuildState,
        filter: PqlExpression?,
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
        filter: PqlExpression?,
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
        filter: PqlExpression?,
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
        filter: PqlExpression?,
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
        filter: PqlExpression?,
        filterScopes: Set<Scope>,
        traceLimit: Long?,
        eventLimit: Long?,
        returnEvents: Boolean,
    ) {
        s.cypher.append(" CALL (log) {")
        emitIndexedTraceMatch(s)
        if (Scope.EVENT in filterScopes) {
            emitEventFilteredTraceSubquery(s, filter!!, traceLimit, eventLimit, returnEvents)
        } else {
            emitRegularTraceSubquery(s, filter, filterScopes, traceLimit, eventLimit, returnEvents)
        }
    }

    private fun emitRegularTraceSubquery(
        s: CypherBuildState,
        filter: PqlExpression?,
        filterScopes: Set<Scope>,
        traceLimit: Long?,
        eventLimit: Long?,
        returnEvents: Boolean,
    ) {
        if (filter != null && filterScopes.any { it != Scope.LOG }) {
            s.cypher.append(" AND (").append(filterRenderer.renderWithHoisting(filter, s)).append(')')
        }
        s.cypher.append(" WITH trace ORDER BY ${indexedTraceOrder(s)}")
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
        filter: PqlExpression,
        traceLimit: Long?,
        eventLimit: Long?,
        returnEvents: Boolean,
    ) {
        s.cypher.append(" CALL (log, trace) { MATCH (trace)-[:HAS_EVENT]->(event:Event)")
        s.cypher.append(" WHERE ").append(filterRenderer.renderWithHoisting(filter, s))
        if (!returnEvents) {
            s.cypher.append(" RETURN count(event) > 0 AS _hasEvents }")
            s.cypher.append(" WITH trace WHERE _hasEvents")
            s.cypher.append(" WITH trace ORDER BY ${indexedTraceOrder(s)}")
            traceLimit?.let { s.cypher.append(" LIMIT ${'$'}traceLimit") }
            s.cypher.append(" RETURN trace }")
            return
        }
        s.cypher.append(" WITH event ORDER BY ${eventOrder(s)}")
        eventLimit?.let { s.cypher.append(" LIMIT ${'$'}eventLimit") }
        s.cypher.append(" RETURN collect(event) AS _events }")
        s.cypher.append(" WITH trace, _events WHERE size(_events) > 0")
        s.cypher.append(" WITH trace, _events ORDER BY ${indexedTraceOrder(s)}")
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
        filter: PqlExpression?,
        filterScopes: Set<Scope>,
        traceLimit: Long?,
        eventLimit: Long?,
    ) {
        if (filter != null && filterScopes.any { it != Scope.LOG }) {
            s.cypher.append(" AND (").append(filterRenderer.renderWithHoisting(filter, s)).append(')')
        }
        s.cypher.append(" WITH trace ORDER BY ${indexedTraceOrder(s)}")
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
        filter: PqlExpression,
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
        s.cypher.append(" WITH trace, _events ORDER BY ${indexedTraceOrder(s)}")
        traceLimit?.let { s.cypher.append(" LIMIT ${'$'}traceLimit") }
        s.cypher.append(" UNWIND _events AS event RETURN trace, event }")
    }

    private fun returnOrder(s: CypherBuildState): String =
        listOf(
            logOrder(s),
            traceOrder(s),
            eventOrder(s),
        ).joinToString(", ")

    private fun eventOrder(s: CypherBuildState): String =
        scopedOrder(s, Scope.EVENT, "event.importOrder")

    private fun traceOrder(s: CypherBuildState): String =
        scopedOrder(s, Scope.TRACE, "trace.importOrder")

    private fun indexedTraceOrder(s: CypherBuildState): String =
        "trace.parentLogId, ${traceOrder(s)}"

    private fun emitIndexedTraceMatch(s: CypherBuildState) {
        s.cypher.append(
            " MATCH (trace:Trace {parentLogId: log.logId})" +
                " WHERE trace.importOrder IS NOT NULL",
        )
    }

    private fun logOrder(s: CypherBuildState): String =
        scopedOrder(s, Scope.LOG, defaultLogOrder(s))

    private fun emitLogLimit(s: CypherBuildState) {
        // A logId anchor binds at most one log, so ranking logs — which for
        // child-scope order keys means scanning every event of the log
        // (O(events), 388ms on Hospital / 730ms on a 561k-event log) — decides
        // nothing. Just cap the row count.
        if (s.plan.source.logId != null) {
            s.cypher.append(" WITH log LIMIT ${'$'}logLimit")
            return
        }
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
