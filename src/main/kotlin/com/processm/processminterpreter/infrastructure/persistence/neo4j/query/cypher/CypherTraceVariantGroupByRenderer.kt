package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.common.OrderKey
import com.processm.processminterpreter.domain.pql.plan.ProjectedColumn
import com.processm.processminterpreter.domain.pql.resolved.Aggregation
import com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression

/**
 * Handles ProcessM's `GROUP BY ^e:attr` semantics.
 *
 * Hoisted event grouping groups whole traces by the sequence of lifted event values.
 * A regular Cypher `GROUP BY event.attr` would collapse events globally and lose that
 * trace-variant hierarchy.
 */
internal class CypherTraceVariantGroupByRenderer(
    private val expressions: CypherExpressionRenderer,
) {
    private val supportedVariantAggregations = setOf("count", "sum", "min", "max")

    fun emitIfNeeded(s: CypherBuildState): Boolean {
        val shape = traceVariantShape(s) ?: return false

        registerTraceVariantColumnAliases(s, shape)
        emitTraceVariantCypher(s, shape)
        return true
    }

    private fun traceVariantShape(s: CypherBuildState): TraceVariantShape? {
        val groupKeys = s.plan.groupBy?.keys ?: return null
        val groupKey = traceVariantGroupKey(groupKeys)
            ?: return null
        val additionalGroupKeys = groupKeys.filterNot { it == groupKey }
        if (additionalGroupKeys.any { Scope.EVENT in s.facts.scopesOf(it) }) return null

        val projectionColumns = s.plan.projection.columns
        val logColumns = projectionColumns.filter { it.scope == Scope.LOG }
        val eventSelectedColumns = projectionColumns.filter { it.isEventAttributeProjection() }
        val traceCountColumns = projectionColumns.filter { it.isTraceCountProjection() }
        if (traceCountColumns.size > 1) return null
        val traceCountColumn = traceCountColumns.singleOrNull()
        val hoistedEventCountColumns = projectionColumns.filter { it.isHoistedEventCountProjection() }
        val variantAggregationColumns = projectionColumns.filter { it.isVariantAggregationProjection() }
        val compactLogGrouping = logColumns.isEmpty() &&
            additionalGroupKeys.none { Scope.LOG in s.facts.scopesOf(it) }
        val selectedAllScopes = s.plan.projection.selectAll.filterValues { it }.keys
        val supportedColumns =
            logColumns.toSet() +
                eventSelectedColumns +
                listOfNotNull(traceCountColumn) +
                hoistedEventCountColumns +
                variantAggregationColumns
        if (projectionColumns.any { it !in supportedColumns }) return null

        return TraceVariantShape(
            groupKey = groupKey,
            additionalGroupValues = additionalGroupKeys.mapIndexed { idx, key ->
                key to traceGroupAlias(idx)
            },
            logColumns = logColumns,
            selectedValues = eventSelectedColumns.mapIndexed { idx, col ->
                col to selectedValuesAlias(idx)
            },
            traceCountColumn = traceCountColumn,
            hoistedEventCountColumns = hoistedEventCountColumns,
            variantAggregationValues = variantAggregationColumns.mapIndexed { idx, col ->
                col to variantAggregationAlias(idx)
            },
            implicitGroupedEventAlias = GROUPED_EVENT_VALUE_ALIAS.takeIf {
                eventSelectedColumns.isEmpty() && projectionColumns.isEmpty()
            },
            includeLogNode = Scope.LOG in selectedAllScopes || projectionColumns.isEmpty() && selectedAllScopes.isEmpty(),
            compactLogGrouping = compactLogGrouping,
            eventAttributeOrder = traceVariantEventAttributeOrder(s, groupKey),
            singleLogScoped = s.plan.source.logId != null,
        )
    }

    private fun traceVariantGroupKey(groupKeys: List<ResolvedExpression>): ResolvedAttribute? =
        groupKeys
            .filterIsInstance<ResolvedAttribute>()
            .singleOrNull { it.baseScope == Scope.EVENT && it.effectiveScope == Scope.TRACE }

    private fun ProjectedColumn.isEventAttributeProjection(): Boolean =
        (expression as? ResolvedAttribute)?.baseScope == Scope.EVENT

    private fun ProjectedColumn.isTraceCountProjection(): Boolean {
        val (aggregation, attribute) = aggregationOnAttribute() ?: return false
        return aggregation.name.equals("count", ignoreCase = true) &&
            attribute.baseScope == Scope.TRACE
    }

    private fun ProjectedColumn.isHoistedEventCountProjection(): Boolean {
        val (aggregation, attribute) = aggregationOnAttribute() ?: return false
        return aggregation.name.equals("count", ignoreCase = true) &&
            attribute.baseScope == Scope.EVENT &&
            attribute.effectiveScope == Scope.TRACE
    }

    private fun ProjectedColumn.isVariantAggregationProjection(): Boolean {
        val (aggregation, attribute) = aggregationOnAttribute() ?: return false
        return scope == Scope.EVENT &&
            attribute.baseScope == Scope.EVENT &&
            aggregation.name.lowercase() in supportedVariantAggregations
    }

    private fun ProjectedColumn.aggregationOnAttribute(): Pair<Aggregation, ResolvedAttribute>? {
        val aggregation = expression as? Aggregation ?: return null
        val attribute = aggregation.argument as? ResolvedAttribute ?: return null
        return aggregation to attribute
    }

    private fun registerTraceVariantColumnAliases(
        s: CypherBuildState,
        shape: TraceVariantShape,
    ) {
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_LOG_ID_ALIAS,
            scope = Scope.LOG,
        )
        s.registerSyntheticColumnAlias(
            alias = TRACE_VARIANT_KEY_ALIAS,
            scope = Scope.TRACE,
        )
        shape.additionalGroupValues.forEach { (_, alias) ->
            s.registerSyntheticColumnAlias(
                alias = alias,
                scope = Scope.TRACE,
            )
        }
        shape.logColumns.forEach(s::registerProjectedColumnAlias)
        shape.traceCountColumn?.let(s::registerProjectedColumnAlias)
        shape.hoistedEventCountColumns.forEach(s::registerProjectedColumnAlias)
        shape.variantAggregationValues.forEach { (col, _) -> s.registerProjectedColumnAlias(col) }
        shape.selectedValues.forEach { (col, _) -> s.registerProjectedColumnAlias(col) }
        shape.implicitGroupedEventAlias?.let { alias ->
            s.registerColumnAlias(
                alias = alias,
                pqlExpression = baseEventAttributeText(shape.groupKey),
                scope = Scope.EVENT,
                materializeNull = materializeProjectedNull(shape.groupKey),
            )
        }
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_NULL_EVENT_COUNT_ALIAS,
            scope = Scope.TRACE,
        )
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_TRACE_COUNT_ALIAS,
            scope = Scope.TRACE,
        )
    }

    private fun emitTraceVariantCypher(
        s: CypherBuildState,
        shape: TraceVariantShape,
    ) {
        emitPerTraceVariantCollection(s, shape)
        emitTraceVariantGrouping(s, shape)
        emitTraceVariantWindow(s, shape)
        emitTraceVariantLogRematch(s, shape)
        emitTraceVariantEventUnwind(s, shape)
        emitTraceVariantReturn(s, shape)
        emitTraceVariantOrder(s, shape)
    }

    private fun emitPerTraceVariantCollection(
        s: CypherBuildState,
        shape: TraceVariantShape,
    ) {
        val groupedValue = expressions.render(shape.groupKey, s)
        if (shape.compactLogGrouping) {
            s.cypher.append(" WITH log.logId AS $TRACE_VARIANT_LOG_ID_ALIAS, trace, event")
        } else {
            s.cypher.append(" WITH log, trace, event")
        }
        s.cypher.append(" ORDER BY ${traceVariantEventOrder(s, shape)}")
        if (shape.compactLogGrouping) {
            s.cypher.append(" WITH $TRACE_VARIANT_LOG_ID_ALIAS, trace")
        } else {
            s.cypher.append(" WITH log, trace")
        }
        shape.additionalGroupValues.forEach { (key, alias) ->
            s.cypher.append(", ${expressions.render(key, s)} AS $alias")
        }
        s.cypher.append(", collect($groupedValue) AS $TRACE_VARIANT_ALIAS")
        shape.selectedValues.forEach { (col, alias) ->
            s.cypher.append(", collect(${expressions.render(col.expression, s)}) AS $alias")
        }
        shape.variantAggregationValues.forEach { (col, alias) ->
            s.cypher.append(", ${expressions.renderAggregation(col.expression as Aggregation, s)} AS $alias")
        }
        val logOrder = if (shape.compactLogGrouping) TRACE_VARIANT_LOG_ID_ALIAS else "log.logId"
        s.cypher.append(" ORDER BY $logOrder, trace.importOrder")
    }

    private fun emitTraceVariantGrouping(
        s: CypherBuildState,
        shape: TraceVariantShape,
    ) {
        s.cypher.append(" WITH ").append(traceVariantGroupingColumns(shape).joinToString(", "))
    }

    private fun traceVariantGroupingColumns(shape: TraceVariantShape): List<String> =
        buildList {
            if (shape.compactLogGrouping) {
                add(TRACE_VARIANT_LOG_ID_ALIAS)
            } else {
                add("log")
            }
            addAll(shape.additionalGroupValues.map { (_, alias) -> alias })
            add(TRACE_VARIANT_ALIAS)
            add("min(trace.importOrder) AS $TRACE_VARIANT_KEY_ALIAS")
            addAll(shape.selectedValues.map { (_, alias) -> alias })
            add("collect(trace.importOrder) AS $TRACE_GROUP_ORDER_ALIAS")
            add("count(trace) AS $TRACE_COUNT_ALIAS")
            addAll(
                shape.variantAggregationValues.map { (col, alias) ->
                    "${variantAggregationCombiner(col.expression as Aggregation, alias)} AS $alias"
                },
            )
        }

    private fun emitTraceVariantWindow(
        s: CypherBuildState,
        shape: TraceVariantShape,
    ) {
        if (!shape.singleLogScoped) {
            return
        }

        val traceLimit = CypherEffectiveLimits.trace(s)
        val traceOffset = s.plan.offsets.trace?.coerceAtLeast(0) ?: 0
        if (traceLimit == null && traceOffset == 0L) {
            return
        }

        s.cypher.append(" ORDER BY ")
            .append(traceVariantOrderTerms(s, shape).joinToString(", "))
        if (traceOffset > 0) {
            val offsetParam = s.bindParam(traceOffset)
            s.cypher.append(" SKIP \$$offsetParam")
        }
        if (traceLimit != null) {
            val limitParam = s.bindParam(traceLimit)
            s.cypher.append(" LIMIT \$$limitParam")
        }
    }

    private fun emitTraceVariantLogRematch(
        s: CypherBuildState,
        shape: TraceVariantShape,
    ) {
        if (shape.compactLogGrouping) {
            s.cypher.append(" MATCH (log:Log {logId: $TRACE_VARIANT_LOG_ID_ALIAS})")
        }
    }

    private fun emitTraceVariantEventUnwind(
        s: CypherBuildState,
        shape: TraceVariantShape,
    ) {
        if (shape.emitsEventRows) {
            val eventValues = shape.selectedValues.firstOrNull()?.second ?: TRACE_VARIANT_ALIAS
            val eventIndexes = traceVariantEventIndexes(s, eventValues)
            if (eventIndexes == null) {
                s.cypher.append(" UNWIND range(0, size($eventValues) - 1) AS $EVENT_INDEX_ALIAS")
            } else {
                s.cypher.append(" WITH *, $eventIndexes AS $EVENT_INDEXES_ALIAS")
                s.cypher.append(" UNWIND $EVENT_INDEXES_ALIAS AS $EVENT_INDEX_ALIAS")
            }
        }
    }

    private fun traceVariantEventIndexes(
        s: CypherBuildState,
        eventValues: String,
    ): String? {
        val eventLimit = CypherEffectiveLimits.event(s)
        val eventOffset = s.plan.offsets.event?.coerceAtLeast(0) ?: 0
        if (eventLimit == null && eventOffset == 0L) {
            return null
        }

        val startParam = s.bindParam(eventOffset)
        val endExpression = if (eventLimit == null) {
            "size($eventValues)"
        } else {
            val endParam = s.bindParam(eventOffset + eventLimit)
            "CASE WHEN size($eventValues) < \$$endParam THEN size($eventValues) ELSE \$$endParam END"
        }
        return "CASE WHEN size($eventValues) <= \$$startParam THEN [] " +
            "ELSE range(\$$startParam, $endExpression - 1) END"
    }

    private fun emitTraceVariantReturn(
        s: CypherBuildState,
        shape: TraceVariantShape,
    ) {
        s.cypher.append(" RETURN ").append(traceVariantReturnColumns(s, shape).joinToString(", "))
    }

    private fun traceVariantReturnColumns(
        s: CypherBuildState,
        shape: TraceVariantShape,
    ): List<String> =
        buildList {
            add("log.logId AS $SYNTHETIC_LOG_ID_ALIAS")
            add(traceVariantLogMetadataProjection(shape))
            if (shape.includeLogNode) add(traceVariantLogNodeProjection(shape))
            add("$TRACE_VARIANT_KEY_ALIAS AS $TRACE_VARIANT_KEY_ALIAS")
            add("size($TRACE_VARIANT_ALIAS) AS $SYNTHETIC_NULL_EVENT_COUNT_ALIAS")
            shape.additionalGroupValues.forEach { (_, alias) ->
                add("$alias AS $alias")
            }
            shape.logColumns.forEach { col ->
                add("${expressions.render(col.expression, s)} AS ${col.alias}")
            }
            shape.traceCountColumn?.let { col ->
                add("$TRACE_COUNT_ALIAS AS ${col.alias}")
            }
            shape.hoistedEventCountColumns.forEach { col ->
                add(
                    "size([value IN $TRACE_VARIANT_ALIAS WHERE value IS NOT NULL]) * " +
                        "$TRACE_COUNT_ALIAS AS ${col.alias}",
                )
            }
            shape.variantAggregationValues.forEach { (col, alias) ->
                add("$alias AS ${col.alias}")
            }
            shape.selectedValues.forEach { (col, alias) ->
                add("$alias[$EVENT_INDEX_ALIAS] AS ${col.alias}")
            }
            shape.implicitGroupedEventAlias?.let { alias ->
                add("$TRACE_VARIANT_ALIAS[$EVENT_INDEX_ALIAS] AS $alias")
            }
            add("$TRACE_COUNT_ALIAS AS $SYNTHETIC_TRACE_COUNT_ALIAS")
        }

    private fun traceVariantLogMetadataProjection(shape: TraceVariantShape): String =
        if (shape.emitsEventRows) {
            conditionalLogMetadataProjection("$EVENT_INDEX_ALIAS = 0")
        } else {
            logMetadataProjection()
        }

    private fun traceVariantLogNodeProjection(shape: TraceVariantShape): String =
        if (shape.emitsEventRows) {
            conditionalLogNodeProjection("$EVENT_INDEX_ALIAS = 0")
        } else {
            logNodeProjection()
        }

    private fun emitTraceVariantOrder(
        s: CypherBuildState,
        shape: TraceVariantShape,
    ) {
        s.cypher.append(" ORDER BY ")
            .append(traceVariantOrderTerms(s, shape).joinToString(", "))
            .append(shape.eventIndexOrder)
    }

    private fun traceVariantEventOrder(s: CypherBuildState, shape: TraceVariantShape): String {
        val eventAttributeOrder = shape.eventAttributeOrder
        val logOrder = if (shape.compactLogGrouping) TRACE_VARIANT_LOG_ID_ALIAS else "log.logId"
        return if (eventAttributeOrder != null) {
            val orderValue = expressions.render(eventAttributeOrder.expression, s)
            "$logOrder, trace.importOrder, $orderValue ${eventAttributeOrder.direction.name}, event.importOrder"
        } else {
            "$logOrder, trace.importOrder, event.importOrder"
        }
    }

    private fun variantAggregationCombiner(
        aggregation: Aggregation,
        alias: String,
    ): String = when (aggregation.name.lowercase()) {
        "count", "sum" -> "sum($alias)"
        "min" -> "min($alias)"
        "max" -> "max($alias)"
        else -> alias
    }

    private fun traceVariantAggregationOrder(
        s: CypherBuildState,
        variantAggregationValues: List<Pair<ProjectedColumn, String>>,
    ): Pair<String, String> {
        val orderKey = s.plan.orderBy.firstOrNull()
        val matchedAlias = orderKey?.let { key ->
            variantAggregationValues.firstOrNull { (col, _) ->
                sameAggregation((col.expression as? Aggregation), key.expression as? Aggregation)
            }?.second
        }
        return (matchedAlias ?: variantAggregationValues.first().second) to (orderKey?.direction?.name ?: "ASC")
    }

    private fun sameAggregation(
        left: Aggregation?,
        right: Aggregation?,
    ): Boolean {
        if (left == null || right == null) return false
        if (!left.name.equals(right.name, ignoreCase = true)) return false
        val leftArg = left.argument as? ResolvedAttribute ?: return false
        val rightArg = right.argument as? ResolvedAttribute ?: return false
        return samePqlAttribute(leftArg, rightArg)
    }

    private fun traceVariantEventAttributeOrder(s: CypherBuildState, groupKey: ResolvedAttribute): OrderKey? {
        val orderKey = s.plan.orderBy.firstOrNull() ?: return null
        if (CypherAggregationInspector.containsAggregation(orderKey.expression)) return null
        val orderAttr = orderKey.expression as? ResolvedAttribute ?: return null
        if (orderAttr.baseScope != Scope.EVENT) return null
        return orderKey.takeIf { samePqlAttribute(orderAttr, groupKey) }
    }

    private fun traceVariantOuterOrderTerms(
        s: CypherBuildState,
        shape: TraceVariantShape,
    ): List<String> {
        val terms = s.plan.orderBy.mapNotNull { orderKey ->
            traceVariantOuterOrderExpression(s, shape, orderKey)?.let { expression ->
                "$expression ${orderKey.direction.name}"
            }
        }
        return if (terms.isEmpty()) terms else terms + TRACE_GROUP_ORDER_ALIAS
    }

    private fun traceVariantOuterOrderExpression(
        s: CypherBuildState,
        shape: TraceVariantShape,
        orderKey: OrderKey,
    ): String? {
        val aggregation = orderKey.expression as? Aggregation
        if (aggregation != null) {
            return TRACE_COUNT_ALIAS.takeIf { isTraceCountAggregation(aggregation) }
        }

        val attribute = orderKey.expression as? ResolvedAttribute ?: return null
        if (attribute.baseScope == Scope.EVENT) return null
        val expressionKey = expressions.exprKey(attribute)
        return shape.additionalGroupValues
            .firstOrNull { (groupExpression, _) -> expressions.exprKey(groupExpression) == expressionKey }
            ?.second
    }

    private fun traceVariantOrderTerms(
        s: CypherBuildState,
        shape: TraceVariantShape,
    ): List<String> =
        if (shape.variantAggregationValues.isNotEmpty()) {
            val order = traceVariantAggregationOrder(s, shape.variantAggregationValues)
            listOf("${order.first} ${order.second}")
        } else {
            traceVariantOuterOrderTerms(s, shape).ifEmpty { listOf(TRACE_GROUP_ORDER_ALIAS) }
        }

    private fun isTraceCountAggregation(aggregation: Aggregation): Boolean {
        if (!aggregation.name.equals("count", ignoreCase = true)) return false
        val argument = aggregation.argument as? ResolvedAttribute ?: return false
        return argument.baseScope == Scope.TRACE
    }

    private data class TraceVariantShape(
        val groupKey: ResolvedAttribute,
        val additionalGroupValues: List<Pair<ResolvedExpression, String>>,
        val logColumns: List<ProjectedColumn>,
        val selectedValues: List<Pair<ProjectedColumn, String>>,
        val traceCountColumn: ProjectedColumn?,
        val hoistedEventCountColumns: List<ProjectedColumn>,
        val variantAggregationValues: List<Pair<ProjectedColumn, String>>,
        val implicitGroupedEventAlias: String?,
        val includeLogNode: Boolean,
        val compactLogGrouping: Boolean,
        val eventAttributeOrder: OrderKey?,
        val singleLogScoped: Boolean,
    ) {
        val emitsEventRows: Boolean
            get() = selectedValues.isNotEmpty() || implicitGroupedEventAlias != null

        val eventIndexOrder: String
            get() = if (emitsEventRows) ", $EVENT_INDEX_ALIAS" else ""
    }

    private fun traceGroupAlias(index: Int): String = "_trace_group_$index"

    private fun selectedValuesAlias(index: Int): String = "_selected_values_$index"

    private fun variantAggregationAlias(index: Int): String = "_variant_agg_$index"
}

private const val TRACE_VARIANT_ALIAS = "_trace_variant_"
private const val TRACE_VARIANT_LOG_ID_ALIAS = "_trace_variant_log_id_"
private const val TRACE_VARIANT_KEY_ALIAS = "_trace_variant_key_"
private const val TRACE_GROUP_ORDER_ALIAS = "_trace_group_order_"
private const val TRACE_COUNT_ALIAS = "_order_0"
private const val EVENT_INDEX_ALIAS = "_event_idx_"
private const val EVENT_INDEXES_ALIAS = "_event_indexes_"
private const val GROUPED_EVENT_VALUE_ALIAS = "_grouped_event_value_"
