package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.ast.PqlExpression
import com.processm.processminterpreter.pql.catalog.Scope

/**
 * ProcessM first chooses component groups with WHERE, then evaluates expressions
 * over those component ids. In particular, choosing a log with an event predicate
 * does not restrict the descendants read by count(^^e:name).
 *
 * Each aggregate below reads its own physical scope from the selected members.
 * This also avoids multiplying trace/log values by the number of child events,
 * and keeps sums and averages correct when different components have equal values.
 */
internal class CypherScopedAggregationRenderer(private val expressions: CypherExpressionRenderer) {
    fun emitIfNeeded(s: CypherBuildState): Boolean {
        if (!supports(s)) return false

        val aggregations = (s.plan.projection.columns.map { it.expression } + s.plan.orderBy.map { it.expression })
            .flatMap(CypherAggregationInspector::aggregationsIn)
            .distinctBy(expressions::exprKey)
        val logGrouped = grouped(s, Scope.LOG, aggregations)
        val traceGrouped = grouped(s, Scope.TRACE, aggregations)

        emitLogs(s, logGrouped)
        emitAggregations(s, Scope.LOG, aggregations)
        emitOrderAggregations(s, Scope.LOG)
        emitChildren(s, Scope.TRACE, logGrouped, traceGrouped)
        emitAggregations(s, Scope.TRACE, aggregations)
        emitOrderAggregations(s, Scope.TRACE)
        val projectedEvents = s.plan.projection.columns.any { it.scope == Scope.EVENT }
        if (projectedEvents) {
            emitChildren(s, Scope.EVENT, logGrouped || traceGrouped, grouped(s, Scope.EVENT, aggregations))
            emitAggregations(s, Scope.EVENT, aggregations)
            emitOrderAggregations(s, Scope.EVENT)
        } else {
            emitPlaceholderEventCount(s)
        }
        emitReturn(s, logGrouped, projectedEvents)
        return true
    }

    private fun supports(s: CypherBuildState): Boolean {
        if (!s.facts.hasAnyAggregation || s.plan.projection.columns.isEmpty()) return false
        if (s.plan.projection.selectAll.any { it.value && it.key != Scope.LOG }) return false
        val expressions = s.plan.projection.columns.map { it.expression } + s.plan.orderBy.map { it.expression }
        // Ordinary event-only aggregation already has a compact per-trace path.
        // Enter here whenever a higher-scope aggregate needs independent membership.
        return expressions.flatMap(CypherAggregationInspector::aggregationsIn).any { aggregateScope(it) != Scope.EVENT }

    }

    private fun grouped(s: CypherBuildState, scope: Scope, aggregates: List<PqlExpression.Aggregation>): Boolean =
        aggregates.any { aggregateScope(it) == scope } || groupKeys(s, scope).isNotEmpty()

    private fun aggregateScope(aggregation: PqlExpression.Aggregation): Scope =
        aggregation.scope ?: CypherScopeBindings.attributes(aggregation.argument).maxOf { it.effectiveScope }

    private fun groupKeys(s: CypherBuildState, scope: Scope): List<PqlExpression> =
        s.plan.groupBy?.keys.orEmpty().filter { s.facts.scopesOf(it).maxOrNull() == scope }

    private fun membership(s: CypherBuildState, scope: Scope, node: String): String {
        val filter = s.plan.filter ?: return "true"
        val bindings = CypherScopeBindings(scope, node, "_filter")
        bindings.prepare(filter)
        val predicate = bindings.render(filter, expressions, s)
        // WHERE after WITH filters the left-joined rows. Attaching it to the
        // OPTIONAL MATCH would preserve a failed predicate as an unmatched row.
        return "EXISTS { WITH $node${bindings.clauses()} WITH * WHERE $predicate RETURN 1 AS _match }"
    }

    private fun emitLogs(s: CypherBuildState, grouped: Boolean) {
        CypherMatchEmitter.emitLog(s)
        s.cypher.append(" WHERE ").append(membership(s, Scope.LOG, "log"))
        s.cypher.append(" WITH log ORDER BY log.createdAt, log.logId")
        if (!grouped) {
            s.cypher.append(" WITH [log] AS _log_members")
            return
        }
        val keys = renderGroupKeys(s, Scope.LOG, "log")
        s.cypher.append(" WITH ").append((keys + "collect(log) AS _log_members").joinToString(", "))
        s.cypher.append(" WITH * WHERE size(_log_members) > 0")
    }

    private fun emitChildren(s: CypherBuildState, scope: Scope, parentGrouped: Boolean, grouped: Boolean) {
        val parentScope = Scope.entries[scope.ordinal - 1]
        val parent = expressions.nodeVarFor(parentScope)
        val child = expressions.nodeVarFor(scope)
        val parentMembers = members(parentScope)
        val childMembers = members(scope)
        val relation = if (scope == Scope.TRACE) "CONTAINS" else "HAS_EVENT"
        val label = if (scope == Scope.TRACE) "Trace" else "Event"
        val id = if (scope == Scope.TRACE) "traceId" else "eventId"
        val position = "_${child}_position"
        s.cypher.append(" OPTIONAL CALL ($parentMembers) {")
        s.cypher.append(" UNWIND $parentMembers AS $parent")
        s.cypher.append(" CALL ($parent) { MATCH ($parent)-[:$relation]->($child:$label)")
        s.cypher.append(" WHERE ").append(membership(s, scope, child))
        s.cypher.append(" WITH $child ORDER BY $child.importOrder, $child.$id")
        if (!grouped) emitChildPrefix(s, scope)
        s.cypher.append(" RETURN collect($child) AS _selected_children }")
        s.cypher.append(" UNWIND range(0, size(_selected_children) - 1) AS _position")
        s.cypher.append(" WITH $parent, _selected_children[_position] AS $child, _position")
        val keys = renderGroupKeys(s, scope, child)
        when {
            grouped -> s.cypher.append(" WITH ").append(
                (keys + "collect($child) AS $childMembers" + "min(_position) AS $position").joinToString(", "),
            )
            parentGrouped -> s.cypher.append(
                " WITH _position AS $position, collect($child) AS $childMembers",
            )
            else -> s.cypher.append(" WITH [$child] AS $childMembers, _position AS $position")
        }
        s.cypher.append(" RETURN $childMembers, $position ORDER BY $position")
        if (grouped) emitChildPrefix(s, scope)
        s.cypher.append(" }")
    }

    private fun renderGroupKeys(s: CypherBuildState, scope: Scope, node: String): List<String> =
        groupKeys(s, scope).mapIndexed { index, key ->
            val alias = "_${scope.name.lowercase()}_group_$index"
            val attributes = CypherScopeBindings.attributes(key)
            if (attributes.none { it.baseScope != it.effectiveScope }) {
                return@mapIndexed "${expressions.render(key, s)} AS $alias"
            }
            // The group signature uses the ordinary physical path, just as
            // TranslatedQuery.selectInnerGroup(ignoreHoisting=true). WHERE is
            // evaluated in this same join, so an event predicate may narrow the
            // sequence key even though a hoisted aggregate later reads all events.
            val bindings = CypherScopeBindings(scope, node, "_group")
            val unhoisted = dropHoisting(key)
            bindings.prepare(unhoisted)
            s.plan.filter?.let(bindings::prepare)
            val rendered = bindings.render(unhoisted, expressions, s)
            val filter = s.plan.filter?.let { bindings.render(it, expressions, s) } ?: "true"
            val deepest = attributes.maxBy { it.baseScope }
            val component = bindings.node(deepest.baseScope, deepest.baseScope)
            val sequenceAlias = "_group_sequence_$index"
            s.cypher.append(" CALL ($node) {${bindings.clauses()} WITH * WHERE $filter")
            s.cypher.append(" WITH $component, $rendered AS _group_value ORDER BY $component.importOrder")
            s.cypher.append(" RETURN [item IN collect({value: _group_value}) | item.value] AS $sequenceAlias }")
            "$sequenceAlias AS $alias"
        }

    private fun dropHoisting(expression: PqlExpression): PqlExpression = when (expression) {
        is PqlExpression.Attribute -> expression.copy(effectiveScope = expression.baseScope)
        is PqlExpression.Binary -> expression.copy(left = dropHoisting(expression.left), right = dropHoisting(expression.right))
        is PqlExpression.Unary -> expression.copy(operand = dropHoisting(expression.operand))
        is PqlExpression.Call -> expression.copy(arguments = expression.arguments.map(::dropHoisting))
        else -> expression
    }

    private fun members(scope: Scope): String = "_${scope.name.lowercase()}_members"

    private fun emitChildPrefix(s: CypherBuildState, scope: Scope) {
        if (scope == Scope.TRACE) {
            emitTracePrefix(s)
        } else if (s.plan.orderBy.none { Scope.EVENT in s.facts.scopesOf(it.expression) }) {
            emitEventPrefix(s)
        }
    }

    private fun emitTracePrefix(s: CypherBuildState) {
        if (s.plan.orderBy.any { Scope.TRACE in s.facts.scopesOf(it.expression) || Scope.EVENT in s.facts.scopesOf(it.expression) }) return
        val limit = CypherEffectiveLimits.trace(s) ?: return
        val prefix = (limit.coerceAtLeast(1) + (s.plan.offsets.trace ?: 0).coerceAtLeast(0))
            .coerceAtMost(Int.MAX_VALUE.toLong())
        s.bindNamedParam("aggregateTracePrefix", prefix)
        s.cypher.append(" LIMIT ${'$'}aggregateTracePrefix")
    }

    private fun emitAggregations(
        s: CypherBuildState,
        scope: Scope,
        aggregations: List<PqlExpression.Aggregation>,
    ) {
        val members = members(scope)
        aggregations.filter { aggregateScope(it) == scope }.forEach { aggregation ->
            val alias = "_scope_agg_${s.nextCaggId()}"
            val bindings = CypherScopeBindings(scope, "_member", "_argument")
            bindings.prepare(aggregation.argument)
            val argument = bindings.render(aggregation.argument, expressions, s)
            val attributes = CypherScopeBindings.attributes(aggregation.argument)
            val attribute = attributes.first()
            val identity = bindings.node(attribute.baseScope, attribute.effectiveScope)
            s.cypher.append(" CALL ($members) { UNWIND coalesce($members, []) AS _member")
            s.cypher.append(bindings.clauses())
            val distinct = if (attributes.map { it.baseScope to it.effectiveScope }.distinct().size == 1 && attribute.baseScope >= scope) {
                ""
            } else {
                " DISTINCT"
            }
            s.cypher.append(" WITH$distinct $identity AS _component, $argument AS _value")
            val rendered = if (aggregation.name.equals("sum", ignoreCase = true)) {
                "CASE WHEN count(_value) = 0 THEN null ELSE sum(_value) END"
            } else {
                "${aggregation.name}(_value)"
            }
            s.cypher.append(" RETURN $rendered AS $alias }")
            s.registerComplexAggregationAlias(expressions.exprKey(aggregation), alias)
        }
    }

    private fun emitPlaceholderEventCount(s: CypherBuildState) {
        s.cypher.append(" CALL (_trace_members) { UNWIND coalesce(_trace_members, []) AS trace")
        s.cypher.append(" CALL (trace) { MATCH (trace)-[:HAS_EVENT]->(event:Event)")
        s.cypher.append(" WHERE ").append(membership(s, Scope.EVENT, "event"))
        if (CypherEffectiveLimits.event(s) != null) {
            s.cypher.append(" WITH event")
            emitEventPrefix(s)
        }
        s.cypher.append(" RETURN count(event) AS _event_count }")
        // Descendants of grouped traces are grouped by their filtered position
        // within each source trace. Their number is the maximum selected length.
        s.cypher.append(" RETURN coalesce(max(_event_count), 0) AS $SYNTHETIC_NULL_EVENT_COUNT_ALIAS }")
    }

    private fun emitOrderAggregations(s: CypherBuildState, scope: Scope) {
        s.plan.orderBy.map { it.expression }
            .flatMap(CypherAggregationInspector::aggregationsIn)
            .distinctBy(expressions::exprKey)
            .filter { aggregateScope(it) == scope }
            .forEach { aggregation ->
                val members = members(scope)
                val alias = "_scope_order_${s.nextCaggId()}"
                val bindings = CypherScopeBindings(scope, "_member", "_ordering")
                val argument = dropHoisting(aggregation.argument)
                bindings.prepare(argument)
                s.plan.filter?.let(bindings::prepare)
                val value = bindings.render(argument, expressions, s)
                val predicate = s.plan.filter?.let { bindings.render(it, expressions, s) } ?: "true"
                s.cypher.append(" CALL ($members) { UNWIND coalesce($members, []) AS _member")
                s.cypher.append(bindings.clauses())
                s.cypher.append(" WITH * WHERE $predicate WITH $value AS _value")
                val result = if (aggregation.name.equals("sum", ignoreCase = true)) {
                    "CASE WHEN count(_value) = 0 THEN null ELSE sum(_value) END"
                } else {
                    "${aggregation.name}(_value)"
                }
                s.cypher.append(" RETURN $result AS $alias }")
                s.registerOrderAlias(expressions.exprKey(aggregation), alias)
            }
    }

    private fun emitEventPrefix(s: CypherBuildState) {
        val limit = CypherEffectiveLimits.event(s) ?: return
        val prefix = (limit.coerceAtLeast(1) + (s.plan.offsets.event ?: 0).coerceAtLeast(0)).coerceAtMost(Int.MAX_VALUE.toLong())
        s.bindNamedParam("aggregateEventPrefix", prefix)
        s.cypher.append(" LIMIT ${'$'}aggregateEventPrefix")
    }

    private fun emitReturn(s: CypherBuildState, logGrouped: Boolean, projectedEvents: Boolean) {
        s.cypher.append(" WITH *, head(_log_members) AS log, head(_trace_members) AS trace")
        if (projectedEvents) s.cypher.append(", head(_event_members) AS event")
        val nodeVariables = mapOf(
            (Scope.LOG to Scope.LOG) to "log",
            (Scope.TRACE to Scope.TRACE) to "trace",
            (Scope.EVENT to Scope.EVENT) to "event",
        )
        val columns = buildList {
            add("[member IN _log_members | member.logId] AS $SYNTHETIC_LOG_ID_ALIAS")
            add("head(_log_members).createdAt AS $SYNTHETIC_LOG_ORDER_ALIAS")
            add("[member IN coalesce(_trace_members, []) | member.traceId] AS $SYNTHETIC_TRACE_ID_ALIAS")
            add("_trace_position AS $SYNTHETIC_TRACE_ORDER_ALIAS")
            add("size(coalesce(_trace_members, [])) > 0 AS $SYNTHETIC_TRACE_PRESENT_ALIAS")
            add(if (logGrouped) "log { .classifiers, .extensions } AS $SYNTHETIC_LOG_METADATA_ALIAS" else logMetadataProjection())
            if (s.plan.projection.selectAll[Scope.LOG] == true) add("log")
            if (projectedEvents) {
                add("size(coalesce(_event_members, [])) > 0 AS $SYNTHETIC_EVENT_PRESENT_ALIAS")
                add("_event_position AS $SYNTHETIC_EVENT_GROUP_ORDER_ALIAS")
            } else {
                add(SYNTHETIC_NULL_EVENT_COUNT_ALIAS)
            }
            s.plan.projection.columns.forEach { column ->
                s.registerProjectedColumnAlias(column)
                add(s.withAttributeNodeVariables(nodeVariables) { "${expressions.render(column.expression, s)} AS ${column.alias}" })
            }
        }
        s.registerSyntheticColumnAlias(SYNTHETIC_LOG_ID_ALIAS, Scope.LOG)
        s.registerSyntheticColumnAlias(SYNTHETIC_LOG_ORDER_ALIAS, Scope.LOG)
        s.registerSyntheticColumnAlias(SYNTHETIC_TRACE_ID_ALIAS, Scope.TRACE)
        s.registerSyntheticColumnAlias(SYNTHETIC_TRACE_ORDER_ALIAS, Scope.TRACE)
        if (projectedEvents) {
            s.registerSyntheticColumnAlias(SYNTHETIC_EVENT_GROUP_ORDER_ALIAS, Scope.EVENT)
        } else {
            s.registerSyntheticColumnAlias(SYNTHETIC_NULL_EVENT_COUNT_ALIAS, Scope.TRACE)
        }
        s.cypher.append(" RETURN ").append(columns.joinToString(", "))
        fun orderAt(scope: Scope): List<String> = s.plan.orderBy.filter { key ->
            s.facts.scopesOf(key.expression).maxOrNull() == scope
        }.map { key ->
            val aliases = CypherAggregationInspector.aggregationsIn(key.expression).associate { aggregation ->
                val key = expressions.exprKey(aggregation)
                key to requireNotNull(s.orderAlias(key))
            }
            val value = s.withExpressionAliases(aliases) {
                s.withAttributeNodeVariables(nodeVariables) { expressions.render(key.expression, s) }
            }
            "$value ${key.direction.name}"
        }
        val order = orderAt(Scope.LOG) + listOf(SYNTHETIC_LOG_ORDER_ALIAS, SYNTHETIC_LOG_ID_ALIAS) +
            orderAt(Scope.TRACE) + SYNTHETIC_TRACE_ORDER_ALIAS +
            orderAt(Scope.EVENT) + if (projectedEvents) listOf(SYNTHETIC_EVENT_GROUP_ORDER_ALIAS) else emptyList()
        s.cypher.append(" ORDER BY ").append(order.joinToString(", "))
    }
}
