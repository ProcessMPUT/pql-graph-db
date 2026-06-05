package com.processm.processminterpreter.domain.pql.semantics

import com.processm.processminterpreter.domain.pql.catalog.AttributeKind
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.domain.pql.catalog.Type
import com.processm.processminterpreter.domain.pql.common.HierarchicalLimits
import com.processm.processminterpreter.domain.pql.common.HierarchicalOffsets
import com.processm.processminterpreter.domain.pql.plan.GroupBySpec
import com.processm.processminterpreter.domain.pql.plan.LogicalPlan
import com.processm.processminterpreter.domain.pql.plan.LogicalSource
import com.processm.processminterpreter.domain.pql.plan.ProjectedColumn
import com.processm.processminterpreter.domain.pql.plan.Projection
import com.processm.processminterpreter.domain.pql.resolved.Aggregation
import com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression
import com.processm.processminterpreter.domain.pql.resolved.ResolvedQuery
import com.processm.processminterpreter.domain.pql.resolved.ScalarFunction
import com.processm.processminterpreter.domain.pql.resolved.TypedBinaryOp
import com.processm.processminterpreter.domain.pql.resolved.TypedLiteral
import com.processm.processminterpreter.domain.pql.resolved.TypedUnaryOp
import com.processm.processminterpreter.domain.pql.resolved.ValidatedQuery

/**
 * Translates a [ValidatedQuery] into a backend-agnostic [LogicalPlan].
 *
 * Responsibilities:
 *  - flatten SELECT columns into [ProjectedColumn]s with derived aliases,
 *  - tag each column with its effective scope (driving XES projection layer),
 *  - compute the transitive set of used scopes from all clauses for [LogicalSource],
 *  - translate GROUP BY into [GroupBySpec] (with `hasAggregation` flag),
 *  - translate ORDER BY / LIMIT / OFFSET into their plan counterparts,
 *  - collapse star columns into per-scope [Projection.selectAll] flags.
 *
 * No Cypher, no Neo4j, no function semantics are decided here — only structure.
 */
class Planner(
    private val catalog: StandardAttributeCatalog = StandardAttributeCatalog,
) {

    fun plan(
        validated: ValidatedQuery,
        logId: String? = null,
        dataStoreId: String? = null,
        defaultLimits: HierarchicalLimits = HierarchicalLimits(),
    ): LogicalPlan = when (val q = validated.query) {
        is ResolvedQuery.Select -> planSelect(q, logId, dataStoreId, defaultLimits)
        is ResolvedQuery.Delete -> planDelete(q, logId, dataStoreId)
    }

    private fun planSelect(
        q: ResolvedQuery.Select,
        logId: String?,
        dataStoreId: String?,
        defaultLimits: HierarchicalLimits,
    ): LogicalPlan.Select {
        val projection = buildProjection(q)
        val hasAgg = q.columns.any { col -> col.expression?.let { containsAggregation(it) } == true }
        val groupBy = when {
            q.groupBy.isEmpty() && !hasAgg -> null
            else -> GroupBySpec(keys = q.groupBy, hasAggregation = hasAgg)
        }

        val usedScopes = buildSet {
            add(q.from)
            projection.columns.forEach { add(it.scope) }
            projection.selectAll.filterValues { it }.keys.forEach { add(it) }
            q.where?.let { addAll(scopesOf(it)) }
            q.groupBy.forEach { addAll(scopesOf(it)) }
            q.orderBy.forEach { addAll(scopesOf(it.expression)) }
        }

        return LogicalPlan.Select(
            source = LogicalSource(fromScope = q.from, usedScopes = usedScopes, logId = logId, dataStoreId = dataStoreId),
            projection = projection,
            filter = q.where,
            groupBy = groupBy,
            orderBy = q.orderBy,
            limits = q.limit,
            offsets = q.offset,
            defaultLimits = defaultLimits,
            location = q.location,
        )
    }

    private fun planDelete(q: ResolvedQuery.Delete, logId: String?, dataStoreId: String?): LogicalPlan.Delete {
        val used = buildSet {
            add(q.from)
            q.where?.let { addAll(scopesOf(it)) }
        }
        return LogicalPlan.Delete(
            source = LogicalSource(fromScope = q.from, usedScopes = used, logId = logId, dataStoreId = dataStoreId),
            filter = q.where,
            target = q.from,
            location = q.location,
        )
    }

    private fun buildProjection(q: ResolvedQuery.Select): Projection {
        val starScopes = mutableMapOf<Scope, Boolean>()
        val projected = mutableListOf<ProjectedColumn>()

        q.columns.forEachIndexed { i, col ->
            if (col.starAt != null) {
                starScopes[col.starAt] = true
                return@forEachIndexed
            }
            val expr = col.expression ?: return@forEachIndexed
            if (col.alias == null && expr is ResolvedAttribute && expr.kind == AttributeKind.CLASSIFIER) {
                classifierProjectionAttributes(expr).forEach { expanded ->
                    addProjectedColumn(projected, expanded, alias = null, index = i, defaultScope = q.from)
                }
                return@forEachIndexed
            }
            addProjectedColumn(projected, expr, col.alias, i, q.from)
        }
        if (q.implicitAll && q.groupBy.isNotEmpty()) {
            applyImplicitGroupByProjection(q, starScopes, projected)
        }
        return Projection(columns = projected, selectAll = starScopes, implicitAll = q.implicitAll)
    }

    private fun applyImplicitGroupByProjection(
        q: ResolvedQuery.Select,
        starScopes: MutableMap<Scope, Boolean>,
        projected: MutableList<ProjectedColumn>,
    ) {
        q.groupBy
            .filterIsInstance<ResolvedAttribute>()
            .groupBy { it.effectiveScope }
            .forEach { (groupedScope, attributes) ->
                Scope.entries
                    .filter { it.ordinal >= groupedScope.ordinal }
                    .forEach(starScopes::remove)

                attributes.forEach { grouped ->
                    addImplicitGroupByAttribute(
                        projected = projected,
                        attr = grouped.dropHoisting(),
                        defaultScope = q.from,
                    )
                }
            }
    }

    private fun addImplicitGroupByAttribute(
        projected: MutableList<ProjectedColumn>,
        attr: ResolvedAttribute,
        defaultScope: Scope,
    ) {
        val attributes = if (attr.kind == AttributeKind.CLASSIFIER) {
            classifierProjectionAttributes(attr)
        } else {
            listOf(attr)
        }
        attributes.forEach { expanded ->
            addProjectedColumn(projected, expanded, alias = null, index = projected.size, defaultScope = defaultScope)
        }
    }

    private fun ResolvedAttribute.dropHoisting(): ResolvedAttribute =
        if (effectiveScope == baseScope) this else copy(effectiveScope = baseScope)

    private fun addProjectedColumn(
        projected: MutableList<ProjectedColumn>,
        expr: ResolvedExpression,
        alias: String?,
        index: Int,
        defaultScope: Scope,
    ) {
        val derivedAlias = alias ?: deriveAlias(expr, index)
        val scope = dominantScope(expr) ?: defaultScope
        if (alias == null && projected.any { it.alias == derivedAlias }) return
        projected += ProjectedColumn(expression = expr, alias = derivedAlias, scope = scope)
    }

    private fun classifierProjectionAttributes(attr: ResolvedAttribute): List<ResolvedAttribute> {
        val keys = attr.classifierKeys.ifEmpty { return listOf(attr) }
        return keys.map { key ->
            val standard = catalog.lookup(attr.baseScope, key)
            if (standard != null) {
                attr.copy(
                    name = key,
                    kind = AttributeKind.STANDARD,
                    xesStandardName = standard.canonicalName,
                    classifierName = null,
                    classifierKeys = emptyList(),
                    type = standard.type,
                )
            } else {
                attr.copy(
                    name = key,
                    kind = AttributeKind.CUSTOM,
                    xesStandardName = null,
                    wasBracketed = true,
                    classifierName = null,
                    classifierKeys = emptyList(),
                    type = Type.UNKNOWN,
                )
            }
        }
    }

    /**
     * Choose which hierarchy level owns this projected column.
     * Rule: aggregations are attributed to the scope of their argument; otherwise the
     * deepest attribute scope present (EVENT > TRACE > LOG). No attributes ⇒ null
     * (caller falls back to FROM scope).
     */
    private fun dominantScope(expr: ResolvedExpression): Scope? =
        when (expr) {
            is Aggregation -> expr.scope ?: scopesOf(expr.argument).maxByOrNull { it.ordinal }
            else -> scopesOf(expr).maxByOrNull { it.ordinal }
        }

    private fun scopesOf(expr: ResolvedExpression): Set<Scope> = when (expr) {
        is ResolvedAttribute -> setOf(expr.effectiveScope)
        is Aggregation -> expr.scope?.let { setOf(it) } ?: scopesOf(expr.argument)
        is ScalarFunction -> expr.scope?.let { setOf(it) } ?: expr.arguments.flatMap { scopesOf(it) }.toSet()
        is TypedBinaryOp -> scopesOf(expr.left) + scopesOf(expr.right)
        is TypedUnaryOp -> scopesOf(expr.operand)
        is TypedLiteral -> expr.scope?.let { setOf(it) } ?: emptySet()
        is com.processm.processminterpreter.domain.pql.resolved.ResolvedInList ->
            expr.values.flatMap { scopesOf(it) }.toSet()
    }

    /**
     * Derive a RETURN alias for a projected column.
     *
     * Attribute aliases are prefixed with the effective scope letter
     * (`l_` / `t_` / `e_`) so multi-scope queries like
     * `SELECT l:name, t:name, e:name` don't all land on the same alias
     * and crash Cypher with "multiple result columns with the same name".
     * The scope prefix also lets downstream consumers (HierarchyReconstructor,
     * XESJsonConverter) tell which level owns which projection value.
     */
    private fun deriveAlias(expr: ResolvedExpression, index: Int): String = when (expr) {
        is ResolvedAttribute -> {
            val base = (expr.xesStandardName ?: expr.name).replace(':', '_')
            "${scopePrefix(expr.effectiveScope)}_$base"
        }
        is Aggregation -> "${expr.name}_${index}"
        is ScalarFunction -> "${expr.name}_${index}"
        else -> "col_$index"
    }

    private fun scopePrefix(scope: Scope): String = when (scope) {
        Scope.LOG -> "l"
        Scope.TRACE -> "t"
        Scope.EVENT -> "e"
    }

    private fun containsAggregation(expr: ResolvedExpression): Boolean = when (expr) {
        is Aggregation -> true
        is TypedBinaryOp -> containsAggregation(expr.left) || containsAggregation(expr.right)
        is TypedUnaryOp -> containsAggregation(expr.operand)
        is ScalarFunction -> expr.arguments.any { containsAggregation(it) }
        is com.processm.processminterpreter.domain.pql.resolved.ResolvedInList ->
            expr.values.any { containsAggregation(it) }
        is ResolvedAttribute, is TypedLiteral -> false
    }
}
