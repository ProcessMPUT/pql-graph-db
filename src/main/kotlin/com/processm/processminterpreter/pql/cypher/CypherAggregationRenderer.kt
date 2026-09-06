package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.catalog.AttributeKind
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.plan.ProjectedColumn
import com.processm.processminterpreter.pql.ast.PqlExpression

internal class CypherAggregationRenderer(
    private val expressions: CypherExpressionRenderer,
) {
    /**
     * Counts hierarchy cardinalities without expanding the hierarchy into one
     * row per event. This targets queries such as
     * `count(l:name), count(^t:name), count(^^e:name)`: each lower-scope count is
     * an independent COUNT subquery anchored at the log.
     */
    fun emitHierarchyCardinalityIfNeeded(s: CypherBuildState): Boolean {
        val columns = hierarchyCardinalityColumns(s) ?: return false
        registerLogAggregatePlaceholderAliases(s, columns)

        CypherMatchEmitter.emitLog(s)
        val countColumns = columns.map { column ->
            val aggregation = column.expression as PqlExpression.Aggregation
            "${hierarchyCardinalityExpression(aggregation)} AS ${column.alias}"
        }
        s.cypher.append(" WITH log, ").append(countColumns.joinToString(", "))
        emitLogAggregatePlaceholderTraceMatch(s)
        s.cypher.append(" WITH log, ")
            .append(columns.joinToString(", ") { it.alias })
            .append(", _placeholder_trace, $PLACEHOLDER_EVENT_COUNT AS $PLACEHOLDER_EVENT_COUNT_ALIAS")

        val returnColumns = buildList {
            add("log.logId AS $SYNTHETIC_LOG_ID_ALIAS")
            add(logMetadataProjection())
            add("_placeholder_trace.traceId AS $SYNTHETIC_TRACE_ID_ALIAS")
            add("_placeholder_trace.importOrder AS $SYNTHETIC_TRACE_ORDER_ALIAS")
            add(
                "CASE WHEN $PLACEHOLDER_EVENT_COUNT_ALIAS < 1 THEN 1 ELSE $PLACEHOLDER_EVENT_COUNT_ALIAS END" +
                    " AS $SYNTHETIC_NULL_EVENT_COUNT_ALIAS",
            )
            addAll(columns.map { it.alias })
        }
        s.cypher.append(" RETURN ").append(returnColumns.joinToString(", "))
        s.cypher.append(" ORDER BY $SYNTHETIC_LOG_ID_ALIAS, $SYNTHETIC_TRACE_ORDER_ALIAS")
        return true
    }

    private fun hierarchyCardinalityColumns(s: CypherBuildState): List<ProjectedColumn>? {
        if (s.plan.source.logId == null) return null
        if (s.plan.filter != null) return null
        if (s.plan.groupBy?.keys?.isNotEmpty() == true || s.plan.orderBy.isNotEmpty()) return null
        if (s.plan.projection.columns.isEmpty() || s.plan.projection.selectAll.any { it.value }) return null
        return s.plan.projection.columns.takeIf { columns ->
            columns.all { column ->
                val aggregation = column.expression as? PqlExpression.Aggregation ?: return@all false
                val attribute = aggregation.argument as? PqlExpression.Attribute ?: return@all false
                column.scope == Scope.LOG &&
                    aggregation.name.equals("count", ignoreCase = true) &&
                    (aggregation.scope ?: attribute.effectiveScope) == Scope.LOG &&
                    attribute.effectiveScope == Scope.LOG &&
                    attribute.kind != AttributeKind.CLASSIFIER
            }
        }
    }

    private fun hierarchyCardinalityExpression(aggregation: PqlExpression.Aggregation): String {
        val attribute = aggregation.argument as PqlExpression.Attribute
        val nodeVar = when (attribute.baseScope) {
            Scope.LOG -> "log"
            Scope.TRACE -> CARDINALITY_TRACE_ALIAS
            Scope.EVENT -> CARDINALITY_EVENT_ALIAS
        }
        val property = expressions.propertyRef(attribute).copy(nodeVar = nodeVar).toCypher()
        return when (attribute.baseScope) {
            Scope.LOG -> "CASE WHEN $property IS NULL THEN 0 ELSE 1 END"
            Scope.TRACE ->
                "COUNT { (log)-[:CONTAINS]->($CARDINALITY_TRACE_ALIAS:Trace)" +
                    " WHERE $property IS NOT NULL }"
            Scope.EVENT ->
                "COUNT { (log)-[:CONTAINS]->(:Trace)-[:HAS_EVENT]->($CARDINALITY_EVENT_ALIAS:Event)" +
                    " WHERE $property IS NOT NULL }"
        }
    }

    /**
     * If the plan needs aggregation, emit a single `WITH` that carries:
     *  - pass-through identity (log, trace, event) so hierarchy reconstruction sees
     *    the original nodes,
     *  - grouping keys as aliased expressions (`<expr> AS _gb_N`),
     *  - sub-aggregations extracted from complex expressions (`<agg> AS _cagg_N`),
     *  - simple aggregations reused directly under their SELECT alias.
     *
     * Non-aggregation queries fall through to the plain RETURN path.
     */
    fun emitIfAggregation(s: CypherBuildState) {
        if (!s.facts.hasAnyAggregation) return

        if (requiresTraceDeduplication(s)) {
            s.cypher.append(" WITH DISTINCT log, trace")
            return
        }

        val precomputedAliases = emitTraceScopedHoistedAggregations(s)
        val columns = buildAggregationWithColumns(s, precomputedAliases)
        if (columns.isNotEmpty()) {
            s.cypher.append(" WITH ").append(columns.joinToString(", "))
        }
    }

    private fun requiresTraceDeduplication(s: CypherBuildState): Boolean {
        if (s.plan.groupBy?.keys?.isNotEmpty() == true) return false
        if (Scope.EVENT !in s.facts.usedScopes) return false
        return aggregationArgumentUsage(s).requiresDistinctTraceRows
    }

    private fun aggregationArgumentUsage(s: CypherBuildState): AggregationArgumentUsage =
        AggregationArgumentUsage(
            trace = s.plan.projection.columns.any { col ->
                s.facts.aggregationArgumentUsesBaseScope(col.expression, Scope.TRACE)
            },
            event = s.plan.projection.columns.any { col ->
                s.facts.aggregationArgumentUsesBaseScope(col.expression, Scope.EVENT)
            },
        )

    private fun buildAggregationWithColumns(
        s: CypherBuildState,
        precomputedAliases: List<String>,
    ): List<String> =
        passThroughBindings(s, precomputedAliases) +
            groupKeyColumns(s) +
            complexAggregationColumns(s)

    private fun passThroughBindings(
        s: CypherBuildState,
        precomputedAliases: List<String>,
    ): List<String> =
        buildList {
            add("log")
            if (Scope.TRACE in s.facts.usedScopes || Scope.EVENT in s.facts.usedScopes) add("trace")
            if (Scope.EVENT in s.facts.usedScopes) add("event")
            addAll(precomputedAliases)
        }

    private fun groupKeyColumns(s: CypherBuildState): List<String> =
        buildList {
            val groupKeys = s.plan.groupBy?.keys ?: return@buildList
            groupKeys.forEachIndexed { idx, key ->
                val alias = "_gb_$idx"
                val rendered = expressions.render(key, s)
                s.registerGroupByAlias(expressions.exprKey(key), alias)
                add("$rendered AS $alias")
            }
            if (groupKeys.any { Scope.EVENT in s.facts.scopesOf(it) }) {
                s.registerSyntheticColumnAlias(
                    alias = SYNTHETIC_EVENT_GROUP_ORDER_ALIAS,
                    scope = Scope.EVENT,
                )
            }
        }

    private fun complexAggregationColumns(s: CypherBuildState): List<String> =
        buildList {
            s.plan.projection.columns.forEach { col ->
                collectComplexAggregations(col.expression, s, this)
            }
        }

    private fun emitTraceScopedHoistedAggregations(s: CypherBuildState): List<String> {
        if (!needsTraceScopedHoistedPrecompute(s)) return emptyList()

        val aliases = linkedMapOf<String, String>()
        traceScopedHoistedAggregations(s).forEach { aggregation ->
            val key = expressions.exprKey(aggregation)
            aliases.getOrPut(key) {
                s.complexAggregationAlias(key) ?: "_cagg_${s.nextCaggId()}".also { alias ->
                    s.registerComplexAggregationAlias(key, alias)
                }
            }
        }
        if (aliases.isEmpty()) return emptyList()

        val returnColumns = traceScopedHoistedAggregations(s)
            .distinctBy(expressions::exprKey)
            .joinToString(", ") { aggregation ->
                val alias = aliases.getValue(expressions.exprKey(aggregation))
                val rendered = s.withHoistedEventNodeVar(TRACE_AGG_EVENT_ALIAS) {
                    expressions.renderAggregation(aggregation, s)
                }
                "$rendered AS $alias"
            }

        s.cypher.append(
            " CALL { WITH trace MATCH (trace)-[:HAS_EVENT]->($TRACE_AGG_EVENT_ALIAS:Event) RETURN $returnColumns }",
        )
        return aliases.values.toList()
    }

    private fun needsTraceScopedHoistedPrecompute(s: CypherBuildState): Boolean {
        val filter = s.plan.filter
        if (filter != null && Scope.EVENT in s.facts.scopesOf(filter)) return false
        val groupKeys = s.plan.groupBy?.keys ?: return false
        return groupKeys.any { Scope.EVENT in s.facts.scopesOf(it) }
    }

    private fun traceScopedHoistedAggregations(s: CypherBuildState): List<PqlExpression.Aggregation> =
        (s.plan.projection.columns.map { it.expression } + s.plan.orderBy.map { it.expression })
            .flatMap(CypherAggregationInspector::aggregationsIn)
            .filter(::isTraceScopedHoistedEventAggregation)

    private fun isTraceScopedHoistedEventAggregation(aggregation: PqlExpression.Aggregation): Boolean {
        val attribute = aggregation.argument as? PqlExpression.Attribute ?: return false
        return attribute.baseScope == Scope.EVENT && attribute.effectiveScope == Scope.TRACE
    }

    private fun collectComplexAggregations(
        expression: PqlExpression,
        s: CypherBuildState,
        columns: MutableList<String>,
    ) {
        fun walk(node: PqlExpression) {
            when (node) {
                is PqlExpression.Aggregation -> Unit
                is PqlExpression.Binary -> {
                    if (CypherAggregationInspector.containsAggregation(node)) {
                        extractAggregationColumns(node.left, s, columns)
                        extractAggregationColumns(node.right, s, columns)
                    }
                }
                is PqlExpression.Unary -> if (CypherAggregationInspector.containsAggregation(node)) {
                    extractAggregationColumns(node.operand, s, columns)
                }
                is PqlExpression.Call -> node.arguments.forEach(::walk)
                is PqlExpression.InList -> node.values.forEach(::walk)
                is PqlExpression.Attribute, is PqlExpression.Literal, is PqlExpression.AttributeRef -> Unit
            }
        }
        if (expression !is PqlExpression.Aggregation) walk(expression)
    }

    private fun extractAggregationColumns(
        expression: PqlExpression,
        s: CypherBuildState,
        columns: MutableList<String>,
    ) {
        when (expression) {
            is PqlExpression.Aggregation -> {
                val key = expressions.exprKey(expression)
                if (s.complexAggregationAlias(key) == null) {
                    val alias = "_cagg_${s.nextCaggId()}"
                    s.registerComplexAggregationAlias(key, alias)
                    columns.add("${expressions.renderAggregation(expression, s)} AS $alias")
                }
            }
            is PqlExpression.Binary -> {
                extractAggregationColumns(expression.left, s, columns)
                extractAggregationColumns(expression.right, s, columns)
            }
            is PqlExpression.Unary -> extractAggregationColumns(expression.operand, s, columns)
            else -> Unit
        }
    }

    private data class AggregationArgumentUsage(
        val trace: Boolean,
        val event: Boolean,
    ) {
        val requiresDistinctTraceRows: Boolean
            get() = trace && !event
    }

    /**
     * Handles bare node-shaped queries ordered by aggregate expressions.
     */
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

    fun emitAggregateOrderByIfNeeded(s: CypherBuildState): Boolean {
        if (s.plan.projection.columns.isNotEmpty()) return false
        if (s.plan.orderBy.none { CypherAggregationInspector.containsAggregation(it.expression) }) return false

        s.cypher.append(" WITH ").append((listOf("log") + orderAliases(s)).joinToString(", "))
        s.cypher.append(" MATCH (log)-[:CONTAINS]->(trace:Trace)")
        s.deferLogProperties()
        s.cypher.append(" RETURN log.logId AS $SYNTHETIC_LOG_KEY_ALIAS, trace, {} AS event")
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
                " ${s.logProperties()} AS _log_node_, null AS _trace_node_, null AS _event_node_",
        )
        s.cypher.append(" UNION ALL")
        s.cypher.append(" WITH $passThrough")
        s.cypher.append(" MATCH (log)-[:CONTAINS]->(trace:Trace)")
        s.cypher.append(
            " RETURN 1 AS _kind, log.logId AS _logKey, trace.traceId AS _traceKey," +
                " trace.importOrder AS _traceOrder, log.name AS _logName," +
                " null AS _log_node_, ${s.xesProperties("trace")} AS _trace_node_, {} AS _event_node_",
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

    private fun registerLogAggregatePlaceholderAliases(
        s: CypherBuildState,
        columns: List<ProjectedColumn>,
    ) {
        columns.forEach(s::registerProjectedColumnAlias)
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_LOG_ID_ALIAS,
            scope = Scope.LOG,
        )
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_TRACE_ID_ALIAS,
            scope = Scope.TRACE,
        )
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_TRACE_ORDER_ALIAS,
            scope = Scope.TRACE,
        )
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_NULL_EVENT_COUNT_ALIAS,
            scope = Scope.TRACE,
        )
    }

    /**
     * Aggregate values above this point were computed over the complete hierarchy.
     * Only the placeholder hierarchy is windowed here: returning every trace and
     * trimming it in [HierarchicalWindowing] wastes driver/network work while each
     * row repeats the log metadata and every aggregate.
     *
     * [HierarchicalWindowing] still applies the actual offset and limit. Therefore
     * Cypher returns the prefix `offset + limit`, rather than applying `SKIP`, so the
     * window is not shifted twice. An unbounded or zero-sized window uses the generic
     * relationship match; `LIMIT 0` would otherwise remove the aggregate log itself.
     */
    private fun emitLogAggregatePlaceholderTraceMatch(s: CypherBuildState) {
        val tracePrefixLimit = placeholderTracePrefixLimit(s)
        if (tracePrefixLimit == null) {
            s.cypher.append(" MATCH (log)-[:CONTAINS]->(_placeholder_trace:Trace)")
            return
        }

        s.bindNamedParam(PLACEHOLDER_TRACE_PREFIX_LIMIT_PARAM, tracePrefixLimit)
        s.cypher.append(
            " CALL (log) {" +
                " MATCH (_placeholder_trace:Trace {parentLogId: log.logId})" +
                " WHERE _placeholder_trace.importOrder IS NOT NULL" +
                " WITH _placeholder_trace" +
                " ORDER BY _placeholder_trace.parentLogId, _placeholder_trace.importOrder" +
                " LIMIT ${'$'}$PLACEHOLDER_TRACE_PREFIX_LIMIT_PARAM" +
                " RETURN _placeholder_trace }",
        )
    }

    private fun placeholderTracePrefixLimit(s: CypherBuildState): Long? {
        val traceLimit = CypherEffectiveLimits.trace(s)
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.takeIf { it > 0 }
            ?: return null
        val traceOffset = s.plan.offsets.trace
            ?.takeIf { it > 0 }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?: 0L
        return (traceOffset + traceLimit).coerceAtMost(Int.MAX_VALUE.toLong())
    }


}

private const val TRACE_AGG_EVENT_ALIAS = "_trace_agg_event"

private const val PLACEHOLDER_EVENT_COUNT = "COUNT { (_placeholder_trace)-[:HAS_EVENT]->(:Event) }"

private const val PLACEHOLDER_EVENT_COUNT_ALIAS = "_placeholder_event_count_"

private const val CARDINALITY_TRACE_ALIAS = "_count_trace"

private const val CARDINALITY_EVENT_ALIAS = "_count_event"

private const val PLACEHOLDER_TRACE_PREFIX_LIMIT_PARAM = "placeholderTracePrefixLimit"
