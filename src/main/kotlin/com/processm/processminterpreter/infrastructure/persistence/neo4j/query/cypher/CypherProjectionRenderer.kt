package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.plan.OrderKey
import com.processm.processminterpreter.domain.pql.plan.ProjectedColumn
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression

internal class CypherProjectionRenderer(
    private val expressions: CypherExpressionRenderer,
) {
    fun emitReturnAndOrder(s: CypherBuildState) {
        emitReturnClause(s)
        emitOrderByClause(s)
    }

    private fun emitReturnClause(s: CypherBuildState) {
        val cols = s.plan.projection.columns
        if (cols.isEmpty()) {
            s.cypher.append(" RETURN log, trace, event")
            return
        }

        s.cypher.append(" RETURN ").append(buildReturnColumns(s, cols).joinToString(", "))
    }

    private fun buildHiddenMetadataColumns(): List<String> = listOf("log AS $SYNTHETIC_LOG_METADATA_ALIAS")

    private fun buildReturnColumns(
        s: CypherBuildState,
        cols: List<ProjectedColumn>,
    ): List<String> =
        buildAggregationIdentityColumns(s) +
            buildProjectionHierarchyKeyColumns(s) +
            buildImplicitMaterializationColumns(s) +
            buildHiddenMetadataColumns() +
            buildSelectAllNodeColumns(s) +
            buildUserProjectionColumns(s, cols) +
            buildHiddenOrderColumns(s)

    private fun buildUserProjectionColumns(
        s: CypherBuildState,
        cols: List<ProjectedColumn>,
    ): List<String> =
        cols.map { col ->
            s.registerProjectedColumnAlias(col)
            "${expressions.render(col.expression, s)} AS ${col.alias}"
        }

    private fun buildSelectAllNodeColumns(s: CypherBuildState): List<String> {
        val selected = s.plan.projection.selectAll.filterValues { it }.keys
        return buildList {
            if (Scope.LOG in selected) add("log")
            if (Scope.TRACE in selected) add("trace")
            if (Scope.EVENT in selected) add("event")
        }
    }

    private fun buildProjectionHierarchyKeyColumns(s: CypherBuildState): List<String> {
        if (s.facts.hasAnyAggregation) return emptyList()

        val identity = mutableListOf<String>()

        if (Scope.TRACE in s.facts.hierarchyScopes || Scope.EVENT in s.facts.hierarchyScopes) {
            identity += logIdColumn(s)
        }

        if (Scope.EVENT in s.facts.hierarchyScopes) {
            identity += traceIdColumn(s)
        }

        return identity
    }

    private fun buildImplicitMaterializationColumns(s: CypherBuildState): List<String> {
        if (Scope.EVENT !in s.facts.materializedScopes || Scope.EVENT in s.facts.projectedScopes) {
            return emptyList()
        }

        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_EVENT_ALIAS,
            scope = Scope.EVENT,
        )
        return listOf("{} AS $SYNTHETIC_EVENT_ALIAS")
    }

    private fun buildHiddenOrderColumns(s: CypherBuildState): List<String> =
        s.plan.orderBy.mapIndexedNotNull { idx, key ->
            if (!CypherAggregationInspector.containsAggregation(key.expression)) return@mapIndexedNotNull null
            val projected = s.plan.projection.columns.any { it.expression == key.expression }
            if (projected) return@mapIndexedNotNull null

            val alias = hiddenOrderAlias(idx)
            s.registerOrderAlias(expressions.exprKey(key.expression), alias)
            "${expressions.render(key.expression, s)} AS $alias"
        }

    /**
     * Implicit per-trace grouping for event-scope aggregations.
     *
     * ProcessM's PQL treats `SELECT count(e:name)` (no explicit `GROUP BY`) as one row
     * per trace. Cypher only groups implicitly when a non-aggregated expression appears
     * alongside aggregations in the RETURN. So for any aggregation query whose USED
     * scopes reach into TRACE or EVENT, we inject synthetic hierarchy keys.
     */
    private fun buildAggregationIdentityColumns(s: CypherBuildState): List<String> {
        if (!s.facts.hasAnyAggregation) return emptyList()
        if (s.plan.groupBy?.keys?.isNotEmpty() == true) {
            return buildExplicitGroupByAggregationIdentityColumns(s)
        }

        val identity = mutableListOf<String>()
        identity += logIdColumn(s)

        if (s.facts.hasEventScopeAggregation) {
            identity += traceIdColumn(s)
            identity += traceOrderColumn(s)
        }
        return identity
    }

    private fun buildExplicitGroupByAggregationIdentityColumns(s: CypherBuildState): List<String> {
        val groupByKeys = s.plan.groupBy?.keys ?: return emptyList()
        val groupsAtEventScope = groupByKeys.any { Scope.EVENT in s.facts.scopesOf(it) }
        val traceScopeGroupKeyAliases = traceScopeGroupKeyAliases(s, groupByKeys)
        val projectsEventRows = s.plan.projection.columns.any { it.scope == Scope.EVENT }
        val aggregatesEventRows = s.facts.hasEventScopeAggregation
        if (canUseTraceGroupsAsIdentity(traceScopeGroupKeyAliases, groupsAtEventScope, projectsEventRows)) {
            return listOf(logIdColumn(s)) +
                traceScopeGroupKeyAliases.mapIndexed { idx, alias -> traceGroupColumn(s, idx, alias) }
        }
        if (!needsFullTraceIdentity(groupsAtEventScope, projectsEventRows, aggregatesEventRows)) return emptyList()

        return listOf(
            logIdColumn(s),
            traceIdColumn(s),
            traceOrderColumn(s),
        )
    }

    private fun traceScopeGroupKeyAliases(
        s: CypherBuildState,
        groupByKeys: List<ResolvedExpression>,
    ): List<String> =
        groupByKeys.mapIndexedNotNull { idx, key ->
            if (Scope.TRACE in s.facts.scopesOf(key)) groupKeyAlias(idx) else null
        }

    private fun canUseTraceGroupsAsIdentity(
        traceScopeGroupKeyAliases: List<String>,
        groupsAtEventScope: Boolean,
        projectsEventRows: Boolean,
    ): Boolean =
        traceScopeGroupKeyAliases.isNotEmpty() && !groupsAtEventScope && !projectsEventRows

    private fun needsFullTraceIdentity(
        groupsAtEventScope: Boolean,
        projectsEventRows: Boolean,
        aggregatesEventRows: Boolean,
    ): Boolean =
        groupsAtEventScope || projectsEventRows || aggregatesEventRows

    private fun emitOrderByClause(s: CypherBuildState) {
        val terms = orderTerms(s)
        if (terms.isNotEmpty()) s.cypher.append(" ORDER BY ").append(terms.joinToString(", "))
    }

    private fun orderTerms(s: CypherBuildState): List<String> =
        if (s.plan.orderBy.isEmpty()) {
            defaultOrderTerms(s)
        } else {
            explicitHierarchicalOrder(s)
        }

    private fun defaultOrderTerms(s: CypherBuildState): List<String> = when {
        s.plan.projection.columns.isEmpty() -> listOf("log.logId", "trace.importOrder", "event.importOrder")
        s.facts.hasAnyAggregation -> defaultAggregationOrderTerms(s)
        else -> defaultProjectionOrderTerms(s)
    }

    private fun defaultAggregationOrderTerms(s: CypherBuildState): List<String> =
        buildList {
            if (s.hasColumnAlias(SYNTHETIC_LOG_ID_ALIAS)) add(SYNTHETIC_LOG_ID_ALIAS)
            if (s.hasColumnAlias(SYNTHETIC_TRACE_ORDER_ALIAS)) add(SYNTHETIC_TRACE_ORDER_ALIAS)
        }

    private fun defaultProjectionOrderTerms(s: CypherBuildState): List<String> =
        buildList {
            add("log.logId")
            if (Scope.TRACE in s.facts.usedScopes || Scope.EVENT in s.facts.usedScopes) add("trace.importOrder")
            if (Scope.EVENT in s.facts.usedScopes) add("event.importOrder")
        }

    private fun explicitHierarchicalOrder(s: CypherBuildState): List<String> {
        val explicit = renderedExplicitOrderTerms(s)

        if (s.facts.hasAnyAggregation) {
            return buildList {
                addAll(explicit.map { it.rendered })
                addAll(defaultAggregationOrderTerms(s))
            }.distinct()
        }

        val scoped = explicit.scopedByHierarchy(s)
        return buildList {
            addAll(scoped.log)
            add("log.logId")
            addAll(scoped.trace)
            if (Scope.TRACE in s.facts.usedScopes || Scope.EVENT in s.facts.usedScopes) {
                add("trace.importOrder")
            }
            addAll(scoped.event)
        }.distinct()
    }

    private fun renderedExplicitOrderTerms(s: CypherBuildState): List<RenderedOrderKey> =
        s.plan.orderBy.map { key ->
            RenderedOrderKey(
                key = key,
                rendered = "${orderKeyExpr(key, s)} ${key.direction.name}",
            )
        }

    private fun List<RenderedOrderKey>.scopedByHierarchy(s: CypherBuildState): ScopedOrderTerms {
        val log = mutableListOf<String>()
        val trace = mutableListOf<String>()
        val event = mutableListOf<String>()
        forEach { order ->
            val scopes = s.facts.scopesOf(order.key.expression)
            when {
                Scope.EVENT in scopes -> event += order.rendered
                Scope.TRACE in scopes -> trace += order.rendered
                Scope.LOG in scopes -> log += order.rendered
                else -> event += order.rendered
            }
        }
        return ScopedOrderTerms(log = log, trace = trace, event = event)
    }

    private fun orderKeyExpr(key: OrderKey, s: CypherBuildState): String {
        val colByExpr = s.plan.projection.columns
            .firstOrNull { it.expression == key.expression }
        if (colByExpr != null && CypherAggregationInspector.containsAggregation(key.expression)) return colByExpr.alias
        s.orderAlias(expressions.exprKey(key.expression))?.let { return it }
        return expressions.render(key.expression, s)
    }

    private fun logIdColumn(s: CypherBuildState): String {
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_LOG_ID_ALIAS,
            scope = Scope.LOG,
        )
        return "log.logId AS $SYNTHETIC_LOG_ID_ALIAS"
    }

    private fun traceIdColumn(s: CypherBuildState): String {
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_TRACE_ID_ALIAS,
            scope = Scope.TRACE,
        )
        return "trace.traceId AS $SYNTHETIC_TRACE_ID_ALIAS"
    }

    private fun traceOrderColumn(s: CypherBuildState): String {
        s.registerSyntheticColumnAlias(
            alias = SYNTHETIC_TRACE_ORDER_ALIAS,
            scope = Scope.TRACE,
        )
        return "trace.importOrder AS $SYNTHETIC_TRACE_ORDER_ALIAS"
    }

    private fun traceGroupColumn(
        s: CypherBuildState,
        index: Int,
        sourceAlias: String,
    ): String {
        val alias = traceGroupOutputAlias(index)
        s.registerSyntheticColumnAlias(
            alias = alias,
            scope = Scope.TRACE,
        )
        return "$sourceAlias AS $alias"
    }

    private data class RenderedOrderKey(
        val key: OrderKey,
        val rendered: String,
    )

    private data class ScopedOrderTerms(
        val log: List<String>,
        val trace: List<String>,
        val event: List<String>,
    )

    private fun hiddenOrderAlias(index: Int): String = "_order_$index"

    private fun groupKeyAlias(index: Int): String = "_gb_$index"

    private fun traceGroupOutputAlias(index: Int): String = "_trace_group_$index"
}
