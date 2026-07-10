package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.catalog.BinaryOperator
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.plan.ProjectedColumn
import com.processm.processminterpreter.pql.ast.PqlExpression

internal class CypherAggregationRenderer(
    private val expressions: CypherExpressionRenderer,
) {
    /**
     * Handles trace-level grouped aggregates, e.g.
     * `SELECT max(^e:timestamp)-min(^e:timestamp) GROUP BY t:name`.
     *
     * The generic aggregation path must preserve event bindings for event-shaped
     * results. For trace-grouped aggregate rows that is actively harmful: it returns
     * one row per event instead of one row per group. This renderer keeps `event` only
     * inside aggregate expressions and returns compact trace-group rows.
     */
    fun emitTraceGroupAggregateIfNeeded(s: CypherBuildState): Boolean {
        val groupCase = traceGroupAggregateCase(s) ?: return false

        registerAliases(s, groupCase)
        emitCypher(s, groupCase)
        return true
    }

    private fun traceGroupAggregateCase(s: CypherBuildState): TraceGroupAggregateCase? {
        val groupKeys = s.plan.groupBy?.keys ?: return null
        if (groupKeys.isEmpty()) return null
        if (!s.facts.hasAnyAggregation) return null
        if (s.plan.projection.columns.isEmpty()) return null
        if (s.plan.projection.selectAll.any { it.value }) return null

        val groupAliases = traceGroupAliases(s, groupKeys) ?: return null

        val groupAliasByExpr = groupAliases.associate { expressions.exprKey(it.expression) to it.alias }
        if (s.plan.projection.columns.any { !canProjectAtTraceGroupScope(it, s, groupAliasByExpr) }) return null
        if (s.plan.orderBy.any { !canOrderAtTraceGroupScope(it.expression, s, groupAliasByExpr) }) return null

        return TraceGroupAggregateCase(
            groupAliases = groupAliases,
            logAliases = logAliases(s, groupAliasByExpr),
            aggregateAliases = aggregateAliases(s),
        )
    }

    private fun traceGroupAliases(
        s: CypherBuildState,
        groupKeys: List<PqlExpression>,
    ): List<ExpressionAlias>? {
        val aliases = groupKeys.mapIndexed { idx, key ->
            if (Scope.EVENT in s.facts.scopesOf(key)) return null
            if (Scope.TRACE !in s.facts.scopesOf(key) && Scope.LOG !in s.facts.scopesOf(key)) return null
            ExpressionAlias(key, groupKeyAlias(idx))
        }
        return aliases.takeIf { groupAliases ->
            groupAliases.any { Scope.TRACE in s.facts.scopesOf(it.expression) }
        }
    }

    private fun canProjectAtTraceGroupScope(
        column: ProjectedColumn,
        s: CypherBuildState,
        groupAliasByExpr: Map<String, String>,
    ): Boolean {
        if (column.scope == Scope.EVENT) return false
        if (CypherAggregationInspector.containsAggregation(column.expression)) return true
        if (column.scope == Scope.LOG) return true
        return expressions.exprKey(column.expression) in groupAliasByExpr
    }

    private fun canOrderAtTraceGroupScope(
        expression: PqlExpression,
        s: CypherBuildState,
        groupAliasByExpr: Map<String, String>,
    ): Boolean =
        CypherAggregationInspector.containsAggregation(expression) ||
            Scope.LOG in s.facts.scopesOf(expression) ||
            expressions.exprKey(expression) in groupAliasByExpr

    private fun aggregateAliases(s: CypherBuildState): List<AggregateAlias> {
        val aliases = linkedMapOf<String, AggregateAlias>()

        fun add(aggregation: PqlExpression.Aggregation) {
            val key = expressions.exprKey(aggregation)
            aliases.getOrPut(key) {
                AggregateAlias(aggregation, aggregateAlias(aliases.size))
            }
        }

        aggregateExpressions(s)
            .flatMap(CypherAggregationInspector::aggregationsIn)
            .forEach(::add)
        return aliases.values.toList()
    }

    private fun aggregateExpressions(s: CypherBuildState): List<PqlExpression> =
        s.plan.projection.columns.map { it.expression } +
            s.plan.orderBy.map { it.expression }

    private fun registerAliases(
        s: CypherBuildState,
        groupCase: TraceGroupAggregateCase,
    ) {
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_LOG_ID_ALIAS,
            scope = Scope.LOG,
        )
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_NULL_EVENT_COUNT_ALIAS,
            scope = Scope.TRACE,
        )
        groupCase.groupAliases.forEachIndexed { idx, _ ->
            s.registerSyntheticColumnAlias(
                alias = traceGroupOutputAlias(idx),
                scope = Scope.TRACE,
            )
        }
        s.plan.projection.columns.forEach(s::registerProjectedColumnAlias)
    }

    private fun emitCypher(
        s: CypherBuildState,
        groupCase: TraceGroupAggregateCase,
    ) {
        s.cypher.append(" WITH ").append(buildTraceGroupWithColumns(s, groupCase).joinToString(", "))
        bindExpressionAliases(s, groupCase)

        s.cypher.append(" RETURN ").append(buildReturnColumns(s, groupCase).joinToString(", "))
        emitOrderBy(s, groupCase)
    }

    private fun buildTraceGroupWithColumns(
        s: CypherBuildState,
        groupCase: TraceGroupAggregateCase,
    ): List<String> =
        buildList {
            add("log.logId AS $SYNTHETIC_LOG_ID_ALIAS")
            add(logMetadataProjection())
            groupCase.logAliases.forEach { add("${expressions.render(it.expression, s)} AS ${it.alias}") }
            groupCase.groupAliases.forEach { add("${expressions.render(it.expression, s)} AS ${it.alias}") }
            add("min(trace.importOrder) AS $TRACE_GROUP_ORDER_ALIAS")
            add("count(event) AS $SYNTHETIC_NULL_EVENT_COUNT_ALIAS")
            groupCase.aggregateAliases.forEach { add("${expressions.renderAggregation(it.aggregation, s)} AS ${it.alias}") }
        }

    private fun buildReturnColumns(
        s: CypherBuildState,
        groupCase: TraceGroupAggregateCase,
    ): List<String> =
        buildList {
            add(SYNTHETIC_LOG_ID_ALIAS)
            add(SYNTHETIC_LOG_METADATA_ALIAS)
            add(SYNTHETIC_NULL_EVENT_COUNT_ALIAS)
            groupCase.groupAliases.forEachIndexed { idx, groupAlias ->
                add("${groupAlias.alias} AS ${traceGroupOutputAlias(idx)}")
            }
            s.plan.projection.columns.forEach { column ->
                add("${expressions.render(column.expression, s)} AS ${column.alias}")
            }
        }

    private fun emitOrderBy(
        s: CypherBuildState,
        groupCase: TraceGroupAggregateCase,
    ) {
        val orderTerms = orderTerms(s, groupCase)
        if (orderTerms.isNotEmpty()) {
            s.cypher.append(" ORDER BY ").append(orderTerms.joinToString(", "))
        }
    }

    private fun orderTerms(
        s: CypherBuildState,
        groupCase: TraceGroupAggregateCase,
    ): List<String> =
        if (s.plan.orderBy.isEmpty()) {
            listOf(TRACE_GROUP_ORDER_ALIAS) + groupCase.groupAliases.map { it.alias }
        } else {
            s.plan.orderBy.map { key ->
                "${renderOrderExpression(key.expression, s)} ${key.direction.name}"
            } + groupCase.groupAliases.map { it.alias }
        }

    private fun renderOrderExpression(
        expression: PqlExpression,
        s: CypherBuildState,
    ): String {
        temporalDifferenceOrderExpression(expression, s)?.let { return it }

        return s.plan.projection.columns
            .firstOrNull { it.expression == expression }
            ?.alias
            ?: expressions.render(expression, s)
    }

    private fun temporalDifferenceOrderExpression(
        expression: PqlExpression,
        s: CypherBuildState,
    ): String? {
        val binary = expression as? PqlExpression.Binary ?: return null
        if (binary.op != BinaryOperator.MINUS) return null
        if (!CypherAggregationInspector.isTemporalAggregation(binary.left)) return null
        if (!CypherAggregationInspector.isTemporalAggregation(binary.right)) return null

        return expressions.renderTemporalDifferenceInDays(binary.left, binary.right, s)
    }

    private fun bindExpressionAliases(
        s: CypherBuildState,
        groupCase: TraceGroupAggregateCase,
    ) {
        groupCase.groupAliases.forEach {
            s.registerGroupByAlias(expressions.exprKey(it.expression), it.alias)
        }
        groupCase.logAliases.forEach {
            s.registerGroupByAlias(expressions.exprKey(it.expression), it.alias)
        }
        groupCase.aggregateAliases.forEach {
            s.registerComplexAggregationAlias(expressions.exprKey(it.aggregation), it.alias)
        }
    }

    private data class TraceGroupAggregateCase(
        val groupAliases: List<ExpressionAlias>,
        val logAliases: List<ExpressionAlias>,
        val aggregateAliases: List<AggregateAlias>,
    )

    private data class ExpressionAlias(
        val expression: PqlExpression,
        val alias: String,
    )

    private data class AggregateAlias(
        val aggregation: PqlExpression.Aggregation,
        val alias: String,
    )

    private fun logAliases(
        s: CypherBuildState,
        groupAliasByExpr: Map<String, String>,
    ): List<ExpressionAlias> {
        val aliases = linkedMapOf<String, ExpressionAlias>()

        fun add(expression: PqlExpression) {
            if (CypherAggregationInspector.containsAggregation(expression)) return
            if (Scope.LOG !in s.facts.scopesOf(expression)) return
            val key = expressions.exprKey(expression)
            if (key in groupAliasByExpr) return
            aliases.getOrPut(key) {
                ExpressionAlias(expression, logExpressionAlias(aliases.size))
            }
        }

        s.plan.projection.columns.forEach { add(it.expression) }
        s.plan.orderBy.forEach { add(it.expression) }
        return aliases.values.toList()
    }

    private fun groupKeyAlias(index: Int): String = "_gb_$index"

    private fun aggregateAlias(index: Int): String = "_cagg_$index"

    private fun logExpressionAlias(index: Int): String = "_log_$index"

    private fun traceGroupOutputAlias(index: Int): String = "_trace_group_$index"

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

    /**
     * Emits ProcessM-compatible placeholder hierarchies for aggregate-only SELECTs.
     *
     * These queries carry aggregate values at log/trace scope while preserving lower
     * hierarchy shape as empty placeholder events, which is not something Cypher's
     * default aggregation output can represent by itself.
     */
    fun emitPlaceholderHierarchyIfNeeded(s: CypherBuildState): Boolean =
        emitLogAggregatePlaceholderHierarchyIfNeeded(s) ||
            emitTraceAggregatePlaceholderHierarchyIfNeeded(s)

    private fun emitLogAggregatePlaceholderHierarchyIfNeeded(s: CypherBuildState): Boolean {
        val placeholderCase = logAggregatePlaceholderCase(s) ?: return false

        registerLogAggregatePlaceholderAliases(s, placeholderCase)
        emitLogAggregatePlaceholderCypher(s, placeholderCase)
        return true
    }

    private fun logAggregatePlaceholderCase(s: CypherBuildState): LogAggregatePlaceholderCase? {
        if (!s.facts.hasAnyAggregation) return null
        if (s.plan.groupBy?.keys?.isNotEmpty() == true) return null
        if (s.plan.projection.columns.isEmpty()) return null
        if (s.plan.projection.columns.any { it.scope != Scope.LOG || !CypherAggregationInspector.containsAggregation(it.expression) }) {
            return null
        }

        return LogAggregatePlaceholderCase(s.plan.projection.columns)
    }

    private fun registerLogAggregatePlaceholderAliases(
        s: CypherBuildState,
        placeholderCase: LogAggregatePlaceholderCase,
    ) {
        placeholderCase.aggregateColumns.forEach(s::registerProjectedColumnAlias)
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

    private fun emitLogAggregatePlaceholderCypher(
        s: CypherBuildState,
        placeholderCase: LogAggregatePlaceholderCase,
    ) {
        val aggregateColumns = placeholderCase.aggregateColumns.map { col ->
            "${expressions.render(col.expression, s)} AS ${col.alias}"
        }
        s.cypher.append(" WITH log, ").append(aggregateColumns.joinToString(", "))
        s.cypher.append(" MATCH (log)-[:CONTAINS]->(_placeholder_trace:Trace)")
        // One row per trace with the event count, not one row per event: the
        // reconstructor only needs how many null placeholder events each trace
        // shows, so shipping O(events) rows (each repeating the log metadata and
        // every aggregate) is pure transfer waste on large logs. An event-less
        // trace still renders one null event, matching the previous shape where
        // OPTIONAL MATCH produced a single unmatched row for it.
        val returnColumns = buildList {
            add("log.logId AS $SYNTHETIC_LOG_ID_ALIAS")
            add(logMetadataProjection())
            add("_placeholder_trace.traceId AS $SYNTHETIC_TRACE_ID_ALIAS")
            add("_placeholder_trace.importOrder AS $SYNTHETIC_TRACE_ORDER_ALIAS")
            add(
                "CASE WHEN $PLACEHOLDER_EVENT_COUNT < 1 THEN 1 ELSE $PLACEHOLDER_EVENT_COUNT END" +
                    " AS $SYNTHETIC_NULL_EVENT_COUNT_ALIAS",
            )
            addAll(placeholderCase.aggregateColumns.map { it.alias })
        }
        s.cypher.append(" RETURN ").append(returnColumns.joinToString(", "))
        s.cypher.append(" ORDER BY $SYNTHETIC_LOG_ID_ALIAS, $SYNTHETIC_TRACE_ORDER_ALIAS")
    }

    private fun emitTraceAggregatePlaceholderHierarchyIfNeeded(s: CypherBuildState): Boolean {
        val placeholderCase = traceAggregatePlaceholderCase(s) ?: return false

        registerTraceAggregatePlaceholderAliases(s, placeholderCase)
        emitTraceAggregatePlaceholderCypher(s, placeholderCase)
        return true
    }

    private fun traceAggregatePlaceholderCase(s: CypherBuildState): TraceAggregatePlaceholderCase? {
        if (!s.facts.hasAnyAggregation) return null
        if (s.plan.groupBy?.keys?.isNotEmpty() == true) return null
        if (s.plan.projection.columns.isEmpty()) return null
        if (s.plan.projection.columns.any {
                it.scope != Scope.TRACE ||
                    !CypherAggregationInspector.containsAggregation(it.expression) ||
                    !s.facts.aggregationArgumentUsesBaseScope(it.expression, Scope.LOG)
            }
        ) {
            return null
        }
        if (s.plan.filter?.let { s.facts.scopesOf(it) - Scope.LOG }?.isNotEmpty() == true) {
            return null
        }

        return TraceAggregatePlaceholderCase(s.plan.projection.columns)
    }

    private fun registerTraceAggregatePlaceholderAliases(
        s: CypherBuildState,
        placeholderCase: TraceAggregatePlaceholderCase,
    ) {
        placeholderCase.aggregateColumns.forEach(s::registerProjectedColumnAlias)
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_LOG_ID_ALIAS,
            scope = Scope.LOG,
        )
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_NULL_EVENT_COUNT_ALIAS,
            scope = Scope.TRACE,
        )
    }

    private fun emitTraceAggregatePlaceholderCypher(
        s: CypherBuildState,
        placeholderCase: TraceAggregatePlaceholderCase,
    ) {
        val aggregateColumns = placeholderCase.aggregateColumns.map { col ->
            "${expressions.render(col.expression, s)} AS ${col.alias}"
        }
        s.cypher.append(" WITH log, ").append(aggregateColumns.joinToString(", "))
        s.cypher.append(" MATCH (log)-[:CONTAINS]->(_placeholder_trace:Trace)")
        s.cypher.append(" OPTIONAL MATCH (_placeholder_trace)-[:HAS_EVENT]->(_placeholder_event:Event)")
        s.cypher.append(" WITH log, ")
            .append(placeholderCase.aggregateColumns.joinToString(", ") { it.alias })
            .append(", _placeholder_trace, count(_placeholder_event) AS _trace_event_count_")
        val returnColumns = buildList {
            add("log.logId AS $SYNTHETIC_LOG_ID_ALIAS")
            add(logMetadataProjection())
            add("max(_trace_event_count_) AS $SYNTHETIC_NULL_EVENT_COUNT_ALIAS")
            addAll(placeholderCase.aggregateColumns.map { it.alias })
        }
        s.cypher.append(" RETURN ").append(returnColumns.joinToString(", "))
        s.cypher.append(" ORDER BY $SYNTHETIC_LOG_ID_ALIAS")
    }

    private data class LogAggregatePlaceholderCase(
        val aggregateColumns: List<ProjectedColumn>,
    )

    private data class TraceAggregatePlaceholderCase(
        val aggregateColumns: List<ProjectedColumn>,
    )
}

private const val TRACE_AGG_EVENT_ALIAS = "_trace_agg_event"

private const val PLACEHOLDER_EVENT_COUNT = "COUNT { (_placeholder_trace)-[:HAS_EVENT]->(:Event) }"
