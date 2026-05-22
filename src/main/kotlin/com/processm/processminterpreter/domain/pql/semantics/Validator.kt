package com.processm.processminterpreter.domain.pql.semantics

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.error.PQLSyntaxException
import com.processm.processminterpreter.domain.pql.error.Problem
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
 * Validates a [ResolvedQuery] against PQL semantic rules.
 *
 *  R1 GROUP BY:        every non-aggregated SELECT attribute must appear in GROUP BY.
 *                      Hoisted group keys also cover their base attribute
 *                      (`select e:name group by ^e:name`), matching ProcessM.
 *  R2 MixedScopes:     `SELECT scope:*` combined with an aggregation at that scope is
 *                      forbidden (ambiguous projection).
 *
 * Output type [ValidatedQuery] is a nominal wrapper around [ResolvedQuery] so downstream
 * code can only receive validated input.
 */
class Validator {

    fun validate(q: ResolvedQuery): ValidatedQuery {
        when (q) {
            is ResolvedQuery.Select -> {
                checkMixedScopes(q)
                checkGroupBy(q)
            }
            is ResolvedQuery.Delete -> Unit // nothing scope-level to validate on DELETE for now
        }
        return ValidatedQuery(q)
    }

    private fun checkMixedScopes(q: ResolvedQuery.Select) {
        val aggScopes: Set<Scope> = q.columns.asSequence()
            .mapNotNull { it.expression }
            .flatMap { collectAggregations(it).asSequence() }
            .flatMap { collectAttributes(it).asSequence() }
            .map { it.effectiveScope }
            .toSet()

        for (col in q.columns) {
            val star = col.starAt ?: continue
            if (star in aggScopes) {
                throw PQLSyntaxException(
                    Problem.MixedScopes,
                    q.location,
                    "SELECT $star:* cannot be combined with $star-level aggregation",
                )
            }
        }
    }

    private fun checkGroupBy(q: ResolvedQuery.Select) {
        q.where?.let { checkNoClassifierInWhere(it) }
        if (q.groupBy.isEmpty()) return

        val grouped: Set<GroupKey> = q.groupBy.filterIsInstance<ResolvedAttribute>()
            .flatMap { groupKeysFor(it) }
            .toSet()
        val deepestGroupedScope = q.groupBy
            .filterIsInstance<ResolvedAttribute>()
            .maxOfOrNull { it.effectiveScope.ordinal }

        for (col in q.columns) {
            val expr = col.expression ?: continue
            if (containsAggregation(expr)) continue
            for (a in collectAttributes(expr)) {
                if (deepestGroupedScope != null && a.effectiveScope.ordinal < deepestGroupedScope) continue
                val key = GroupKey(a.effectiveScope, canonical(a))
                if (key !in grouped) {
                    throw PQLSyntaxException(
                        Problem.AttributeNotInGroupBy,
                        a.location,
                        "Attribute ${a.effectiveScope}:${canonical(a)} must appear in GROUP BY or be aggregated",
                    )
                }
            }
        }
    }

    private fun checkNoClassifierInWhere(expr: ResolvedExpression) {
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

    private fun canonical(a: ResolvedAttribute): String = a.xesStandardName ?: a.name

    private data class GroupKey(val scope: Scope, val name: String)

    private fun groupKeysFor(a: ResolvedAttribute): Set<GroupKey> = buildSet {
        val name = canonical(a)
        add(GroupKey(a.effectiveScope, name))
        add(GroupKey(a.baseScope, name))
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

    private fun collectAttributes(expr: ResolvedExpression): List<ResolvedAttribute> = when (expr) {
        is ResolvedAttribute -> listOf(expr)
        is Aggregation -> collectAttributes(expr.argument)
        is ScalarFunction -> expr.arguments.flatMap { collectAttributes(it) }
        is TypedBinaryOp -> collectAttributes(expr.left) + collectAttributes(expr.right)
        is TypedUnaryOp -> collectAttributes(expr.operand)
        is com.processm.processminterpreter.domain.pql.resolved.ResolvedInList ->
            expr.values.flatMap { collectAttributes(it) }
        is TypedLiteral -> emptyList()
    }

    private fun collectAggregations(expr: ResolvedExpression): List<Aggregation> = when (expr) {
        is Aggregation -> listOf(expr)
        is TypedBinaryOp -> collectAggregations(expr.left) + collectAggregations(expr.right)
        is TypedUnaryOp -> collectAggregations(expr.operand)
        is ScalarFunction -> expr.arguments.flatMap { collectAggregations(it) }
        is com.processm.processminterpreter.domain.pql.resolved.ResolvedInList ->
            expr.values.flatMap { collectAggregations(it) }
        is ResolvedAttribute, is TypedLiteral -> emptyList()
    }
}
