package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.AttributeKind
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
import com.processm.processminterpreter.domain.pql.syntax.OrderDirection

/**
 * Handles bare event grouping, e.g. `GROUP BY e:name`, where ProcessM preserves
 * the log/trace hierarchy and replaces each trace's events with grouped event rows.
 */
internal class CypherEventGroupByOnlyRenderer(
    private val expressions: CypherExpressionRenderer,
) {
    fun emitIfNeeded(s: CypherBuildState): Boolean {
        val groupByCase = eventGroupByOnlyCase(s) ?: return false

        emitEventGroupByOnlyCypher(s, groupByCase)
        return true
    }

    fun emitLimitedBeforeMatchIfNeeded(s: CypherBuildState): Boolean {
        val groupByCase = eventGroupByOnlyCase(s) ?: return false
        if (s.plan.filter != null) return false
        if (s.plan.offsets.log != null || s.plan.offsets.trace != null || s.plan.offsets.event != null) return false

        val logLimit = CypherEffectiveLimits.log(s)
        val traceLimit = CypherEffectiveLimits.trace(s)
        val eventLimit = CypherEffectiveLimits.event(s)
        if (logLimit == null && traceLimit == null && eventLimit == null) return false

        s.bindHierarchyLimitParams(logLimit, traceLimit, eventLimit)

        emitLimitedEventGroupByOnlyCypher(s, groupByCase, logLimit, traceLimit, eventLimit)
        return true
    }

    private fun eventGroupByOnlyCase(s: CypherBuildState): EventGroupByOnlyCase? {
        if (s.plan.projection.columns.isNotEmpty()) return null
        if (s.facts.hasAnyAggregation) return null

        val groupByKeys = s.plan.groupBy?.keys ?: return null
        if (groupByKeys.isEmpty()) return null

        val groupedAttributes = mutableListOf<ResolvedAttribute>()
        for (key in groupByKeys) {
            val attr = key as? ResolvedAttribute ?: return null
            if (attr.baseScope != Scope.EVENT || attr.effectiveScope != Scope.EVENT) return null
            groupedAttributes += if (attr.kind == AttributeKind.CLASSIFIER) {
                expressions.classifierKeyAttributes(attr)
            } else {
                listOf(attr)
            }
        }

        val uniqueAttributes = groupedAttributes.distinctBy { it.xesStandardName ?: it.name }
        if (uniqueAttributes.isEmpty()) return null

        val groupAliases = uniqueAttributes.mapIndexed { idx, attr -> attr to "_gb_$idx" }
        val orderAliases = eventGroupOrderAliases(s, groupAliases) ?: return null

        return EventGroupByOnlyCase(
            groupAliases = groupAliases,
            orderAliases = orderAliases,
        )
    }

    private fun emitEventGroupByOnlyCypher(
        s: CypherBuildState,
        groupByCase: EventGroupByOnlyCase,
    ) {
        s.cypher.append(" WITH log, trace")
        groupByCase.groupAliases.forEach { (attr, alias) ->
            s.cypher.append(", ${expressions.propertyRef(attr, s).toCypher()} AS $alias")
        }
        s.cypher.append(", min(event.importOrder) AS _group_first_event_order_, count(event) AS _event_count_")

        val eventMap =
            groupByCase.groupAliases.joinToString(", ") { (attr, alias) ->
                "${cypherMapKey(expressions.propertyRef(attr, s))}: $alias"
            }
        s.cypher.append(" RETURN log, trace, {$eventMap} AS event")
        s.cypher.append(" ORDER BY log.logId, trace.importOrder, ${eventGroupOrderColumns(groupByCase)}")
    }

    private fun emitLimitedEventGroupByOnlyCypher(
        s: CypherBuildState,
        groupByCase: EventGroupByOnlyCase,
        logLimit: Long?,
        traceLimit: Long?,
        eventLimit: Long?,
    ) {
        CypherMatchEmitter.emitLog(s)
        logLimit?.let {
            s.cypher.append(" WITH log ORDER BY log.logId LIMIT ${'$'}logLimit")
        }

        s.cypher.append(" CALL (log) { MATCH (log)-[:CONTAINS]->(trace:Trace)")
        s.cypher.append(" WITH trace ORDER BY trace.importOrder")
        traceLimit?.let {
            s.cypher.append(" LIMIT ${'$'}traceLimit")
        }

        s.cypher.append(" CALL (trace) { MATCH (trace)-[:HAS_EVENT]->(event:Event)")
        val groupingColumns = groupByCase.groupAliases.map { (attr, alias) ->
            "${expressions.propertyRef(attr, s).toCypher()} AS $alias"
        }
        s.cypher.append(
            " WITH " +
                (groupingColumns + "min(event.importOrder) AS _group_first_event_order_").joinToString(", "),
        )

        val eventMap =
            groupByCase.groupAliases.joinToString(", ") { (attr, alias) ->
                "${cypherMapKey(expressions.propertyRef(attr, s))}: $alias"
            }
        s.cypher.append(
            " WITH {$eventMap} AS event, " +
                groupByCase.orderAliases.joinToString("") { (alias, _) -> "$alias, " } +
                "_group_first_event_order_",
        )
        s.cypher.append(" ORDER BY ").append(eventGroupOrderColumns(groupByCase))
        eventLimit?.let {
            s.cypher.append(" LIMIT ${'$'}eventLimit")
        }
        s.cypher.append(" RETURN event")
        groupByCase.orderAliases.forEachIndexed { idx, (alias, _) ->
            s.cypher.append(", $alias AS _event_order_$idx")
        }
        s.cypher.append(", _group_first_event_order_ }")

        s.cypher.append(" RETURN trace, event")
        groupByCase.orderAliases.indices.forEach { idx ->
            s.cypher.append(", _event_order_$idx")
        }
        s.cypher.append(", _group_first_event_order_ }")

        s.cypher.append(" RETURN log, trace, event")
        groupByCase.orderAliases.indices.forEach { idx ->
            s.cypher.append(", _event_order_$idx")
        }
        s.cypher.append(", _group_first_event_order_")

        s.cypher.append(" ORDER BY log.logId, trace.importOrder, ${eventGroupOuterOrderColumns(groupByCase)}")
    }

    private fun eventGroupOrderAliases(
        s: CypherBuildState,
        groupAliases: List<Pair<ResolvedAttribute, String>>,
    ): List<Pair<String, OrderDirection>>? =
        s.plan.orderBy.map { orderKey ->
            val orderAttr = orderKey.expression as? ResolvedAttribute ?: return null
            if (orderAttr.baseScope != Scope.EVENT || orderAttr.effectiveScope != Scope.EVENT) return null
            val alias =
                groupAliases.firstOrNull { (groupAttr, _) -> samePqlAttribute(groupAttr, orderAttr) }?.second
                    ?: return null
            alias to orderKey.direction
        }

    private fun eventGroupOrderColumns(groupByCase: EventGroupByOnlyCase): String =
        if (groupByCase.orderAliases.isEmpty()) {
            "_group_first_event_order_"
        } else {
            groupByCase.orderAliases.joinToString(", ") { (alias, direction) -> "$alias ${direction.name}" } +
                ", _group_first_event_order_"
        }

    private fun eventGroupOuterOrderColumns(groupByCase: EventGroupByOnlyCase): String =
        if (groupByCase.orderAliases.isEmpty()) {
            "_group_first_event_order_"
        } else {
            groupByCase.orderAliases.mapIndexed { idx, (_, direction) -> "_event_order_$idx ${direction.name}" }
                .joinToString(", ") +
                ", _group_first_event_order_"
        }

    private data class EventGroupByOnlyCase(
        val groupAliases: List<Pair<ResolvedAttribute, String>>,
        val orderAliases: List<Pair<String, OrderDirection>>,
    )
}
