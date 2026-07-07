package com.processm.processminterpreter.pql.semantics

import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.Type
import com.processm.processminterpreter.pql.error.PQLSyntaxException
import com.processm.processminterpreter.pql.error.Problem
import com.processm.processminterpreter.pql.ast.PqlExpression
import com.processm.processminterpreter.pql.ast.PqlQuery
import com.processm.processminterpreter.pql.ast.ValidatedQuery

/**
 * Validates a [PqlQuery] against PQL semantic rules, mirroring the reference
 * ProcessM validation (processm.core querylanguage/Query.kt):
 *
 *  R1 explicit GROUP BY coverage: every non-aggregated attribute in SELECT and
 *     ORDER BY must be covered by a group key. Coverage walks from the
 *     attribute's base scope upward; a key covers when base scope and name
 *     match (hoisting prefixes ignored on both sides, so `group by ^e:name`
 *     covers `e:name` but NOT `t:name`).
 *  R2 MixedScopes: `SELECT scope:*` is forbidden when that scope or any upper
 *     scope carries an explicit GROUP BY.
 *  R3 implicit GROUP BY: an aggregation at a scope without explicit GROUP BY
 *     implicitly groups that scope. Then `scope:*` at that scope or below is
 *     ExplicitSelectAllWithImplicitGroupBy, non-aggregated attributes at that
 *     scope are MissingAttributesInAggregation, and ORDER BY keys at that
 *     scope are dropped (reference emits an OrderByClauseRemoved warning and
 *     removes the clause; we remove it silently).
 *  R4 hoisting is not allowed in SELECT/ORDER BY outside aggregation arguments
 *     (ScopeHoistingInSelectOrOrderBy).
 *
 * Output type [ValidatedQuery] is a nominal wrapper around [PqlQuery] so downstream
 * code can only receive validated input. Because of R3's order-by removal the
 * wrapped query may differ from the input.
 */
class Validator {

    fun validate(q: PqlQuery): ValidatedQuery {
        when (q) {
            is PqlQuery.Select -> {
                q.columns.forEach { col -> col.expression?.let { checkFullyResolved(it) } }
                q.where?.let { checkFullyResolved(it) }
                q.groupBy.forEach { checkFullyResolved(it) }
                q.orderBy.forEach { checkFullyResolved(it.expression) }
                q.where?.let { checkNoAggregationInWhere(it) }
                q.where?.let { checkNoClassifierInWhere(it) }
                checkNoHoistingInSelectOrOrderBy(q)
                checkExplicitGroupByCoverage(q)
                checkMixedScopes(q)
                val effective = validateImplicitGroupBy(q)
                return ValidatedQuery(effective)
            }
            is PqlQuery.Delete -> {
                q.where?.let { checkFullyResolved(it) }
                q.where?.let { checkNoAggregationInWhere(it) }
            }
        }
        return ValidatedQuery(q)
    }

    /**
     * Rejects leftover *surface* nodes — the type-level guarantee that the Planner
     * only ever sees resolved input (formerly enforced by the Raw/Resolved type split).
     */
    private fun checkFullyResolved(expr: PqlExpression) {
        when (expr) {
            is PqlExpression.AttributeRef -> error(
                "Internal error: unresolved attribute reference '${expr.rawText}' at ${expr.location} reached the Validator",
            )
            is PqlExpression.Literal -> check(expr.type != Type.UNKNOWN) {
                "Internal error: untyped literal '${expr.rawText}' at ${expr.location} reached the Validator"
            }
            is PqlExpression.Binary -> {
                checkFullyResolved(expr.left)
                checkFullyResolved(expr.right)
            }
            is PqlExpression.Unary -> checkFullyResolved(expr.operand)
            is PqlExpression.Call -> expr.arguments.forEach { checkFullyResolved(it) }
            is PqlExpression.Aggregation -> checkFullyResolved(expr.argument)
            is PqlExpression.InList -> expr.values.forEach { checkFullyResolved(it) }
            is PqlExpression.Attribute -> Unit
        }
    }

    private fun checkNoAggregationInWhere(expr: PqlExpression) {
        for (aggregation in collectAggregations(expr)) {
            throw PQLSyntaxException(
                Problem.AggregationFunctionInWhere,
                aggregation.location,
                "Aggregation function '${aggregation.name}' is not allowed in WHERE",
            )
        }
    }

    /** Explicit group keys bucketed by their EFFECTIVE scope, compared by BASE scope + name. */
    private fun groupKeyBuckets(q: PqlQuery.Select): Map<Scope, List<PqlExpression.Attribute>> =
        q.groupBy.filterIsInstance<PqlExpression.Attribute>().groupBy { it.effectiveScope }

    /**
     * R2: `SELECT scope:*` with an explicit GROUP BY at that scope or any upper
     * scope is MixedScopes (reference Query.kt:288-298).
     */
    private fun checkMixedScopes(q: PqlQuery.Select) {
        // Reference checks EXPLICIT select-all only (Query.kt:288 filters out the
        // implicit flavor); an implicit `select *` expanded to star columns must
        // not trigger MixedScopes.
        if (q.implicitAll) return
        val buckets = groupKeyBuckets(q)
        for (col in q.columns) {
            val star = col.starAt ?: continue
            var scope: Scope? = star
            while (scope != null) {
                if (buckets[scope].orEmpty().isNotEmpty()) {
                    throw PQLSyntaxException(
                        Problem.MixedScopes,
                        q.location,
                        "SELECT $star:* cannot be combined with GROUP BY at scope $scope",
                    )
                }
                scope = scope.upper
            }
        }
    }

    /**
     * R1: coverage of non-aggregated SELECT and ORDER BY attributes by explicit
     * group keys (reference validateExplicitGroupBy, Query.kt:327-363). Walks
     * from the attribute's base scope upward; a visited non-empty bucket makes
     * grouping "enabled", and the attribute is valid only when some visited
     * bucket contains a key with the same base scope and canonical name
     * (hoisting prefixes ignored on both sides).
     */
    private fun checkExplicitGroupByCoverage(q: PqlQuery.Select) {
        val buckets = groupKeyBuckets(q)
        if (buckets.isEmpty()) return

        val toValidate = q.columns.mapNotNull { it.expression } + q.orderBy.map { it.expression }
        for (expr in toValidate) {
            for (a in attributesOutsideAggregation(expr)) {
                var groupByEnabled = false
                var covered = false
                var scope: Scope? = a.baseScope
                while (scope != null) {
                    val keys = buckets[scope].orEmpty()
                    if (keys.isNotEmpty()) {
                        groupByEnabled = true
                        if (keys.any { it.baseScope == a.baseScope && canonical(it) == canonical(a) }) {
                            covered = true
                            break
                        }
                    }
                    scope = scope.upper
                }
                if (groupByEnabled && !covered) {
                    throw PQLSyntaxException(
                        Problem.AttributeNotInGroupBy,
                        a.location,
                        "Attribute ${a.baseScope}:${canonical(a)} must appear in GROUP BY or be aggregated",
                    )
                }
            }
        }
    }

    /**
     * R3: implicit GROUP BY (reference validateImplicitGroupBy, Query.kt:365-425),
     * run separately over SELECT and then ORDER BY expressions, like the
     * reference. Returns the query with ORDER BY keys at implicitly grouped
     * scopes removed (reference warns OrderByClauseRemoved and clears them).
     */
    private fun validateImplicitGroupBy(q: PqlQuery.Select): PqlQuery.Select {
        val explicitlyGrouped = groupKeyBuckets(q).keys
        var current = q

        fun pass(exprs: List<PqlExpression>) {
            val implicitScopes = exprs
                .flatMap { collectAggregations(it) }
                .map { aggregationScope(it) }
                .filterNot { it in explicitlyGrouped }
                .toSet()

            for (scope in implicitScopes) {
                val (dropped, kept) = current.orderBy.partition { orderKeyScope(it) == scope }
                if (dropped.isNotEmpty()) {
                    // Reference emits an OrderByClauseRemoved warning and clears the clause.
                    current = current.copy(orderBy = kept)
                }

                var atOrBelow: Scope? = scope
                while (atOrBelow != null) {
                    if (!current.implicitAll && current.columns.any { it.starAt == atOrBelow }) {
                        throw PQLSyntaxException(
                            Problem.ExplicitSelectAllWithImplicitGroupBy,
                            current.location,
                            "SELECT $atOrBelow:* cannot be combined with an implicit GROUP BY at scope $scope",
                        )
                    }
                    atOrBelow = atOrBelow.lowerOrNull()
                }

                val nonAggregated = exprs
                    .flatMap { attributesOutsideAggregation(it) }
                    .filter { it.effectiveScope == scope }
                if (nonAggregated.isNotEmpty()) {
                    throw PQLSyntaxException(
                        Problem.MissingAttributesInAggregation,
                        nonAggregated.first().location,
                        "Attributes must be aggregated at implicitly grouped scope $scope: " +
                            nonAggregated.joinToString(", ") { "${it.baseScope}:${canonical(it)}" },
                    )
                }
            }
        }

        pass(current.columns.mapNotNull { it.expression })
        pass(current.orderBy.map { it.expression })
        return current
    }

    /**
     * R4: hoisted attributes in SELECT/ORDER BY are only legal inside
     * aggregation arguments (reference Query.kt:598-611).
     */
    private fun checkNoHoistingInSelectOrOrderBy(q: PqlQuery.Select) {
        val toValidate = q.columns.mapNotNull { it.expression } + q.orderBy.map { it.expression }
        for (expr in toValidate) {
            for (a in attributesOutsideAggregation(expr)) {
                if (a.effectiveScope != a.baseScope) {
                    throw PQLSyntaxException(
                        Problem.ScopeHoistingInSelectOrOrderBy,
                        a.location,
                        "Scope hoisting is not allowed in SELECT or ORDER BY outside aggregation arguments",
                    )
                }
            }
        }
    }

    /** Scope an aggregation groups at: the effective scope of its argument's attributes. */
    private fun aggregationScope(agg: PqlExpression.Aggregation): Scope =
        collectAttributes(agg.argument).map { it.effectiveScope }.maxByOrNull { it.ordinal } ?: Scope.LOG

    /** Scope of an ORDER BY key: deepest effective scope among its non-aggregated attributes. */
    private fun orderKeyScope(key: PqlQuery.OrderKey): Scope? =
        attributesOutsideAggregation(key.expression).map { it.effectiveScope }.maxByOrNull { it.ordinal }

    private fun Scope.lowerOrNull(): Scope? = when (this) {
        Scope.LOG -> Scope.TRACE
        Scope.TRACE -> Scope.EVENT
        Scope.EVENT -> null
    }

    /** Attributes of [expr] that are NOT inside any aggregation's argument subtree. */
    private fun attributesOutsideAggregation(expr: PqlExpression): List<PqlExpression.Attribute> = when (expr) {
        is PqlExpression.Attribute -> listOf(expr)
        is PqlExpression.Aggregation -> emptyList()
        is PqlExpression.Call -> expr.arguments.flatMap { attributesOutsideAggregation(it) }
        is PqlExpression.Binary ->
            attributesOutsideAggregation(expr.left) + attributesOutsideAggregation(expr.right)
        is PqlExpression.Unary -> attributesOutsideAggregation(expr.operand)
        is PqlExpression.InList -> expr.values.flatMap { attributesOutsideAggregation(it) }
        is PqlExpression.Literal, is PqlExpression.AttributeRef -> emptyList()
    }

    private fun checkNoClassifierInWhere(expr: PqlExpression) {
        for (attribute in collectAttributes(expr)) {
            if (attribute.classifierName != null) {
                throw PQLSyntaxException(
                    Problem.ClassifierInWhere,
                    attribute.location,
                    "Classifier references are not allowed in WHERE",
                )
            }
        }
    }

    private fun canonical(a: PqlExpression.Attribute): String = a.xesStandardName ?: a.name

    private fun collectAttributes(expr: PqlExpression): List<PqlExpression.Attribute> = when (expr) {
        is PqlExpression.Attribute -> listOf(expr)
        is PqlExpression.Aggregation -> collectAttributes(expr.argument)
        is PqlExpression.Call -> expr.arguments.flatMap { collectAttributes(it) }
        is PqlExpression.Binary -> collectAttributes(expr.left) + collectAttributes(expr.right)
        is PqlExpression.Unary -> collectAttributes(expr.operand)
        is PqlExpression.InList ->
            expr.values.flatMap { collectAttributes(it) }
        is PqlExpression.Literal, is PqlExpression.AttributeRef -> emptyList()
    }

    private fun collectAggregations(expr: PqlExpression): List<PqlExpression.Aggregation> = when (expr) {
        is PqlExpression.Aggregation -> listOf(expr)
        is PqlExpression.Binary -> collectAggregations(expr.left) + collectAggregations(expr.right)
        is PqlExpression.Unary -> collectAggregations(expr.operand)
        is PqlExpression.Call -> expr.arguments.flatMap { collectAggregations(it) }
        is PqlExpression.InList ->
            expr.values.flatMap { collectAggregations(it) }
        is PqlExpression.Attribute, is PqlExpression.Literal, is PqlExpression.AttributeRef -> emptyList()
    }
}
