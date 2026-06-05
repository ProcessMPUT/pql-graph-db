package com.processm.processminterpreter.domain.pql.semantics

import com.processm.processminterpreter.domain.pql.catalog.BinaryOperator
import com.processm.processminterpreter.domain.pql.syntax.RawAttributeRef
import com.processm.processminterpreter.domain.pql.syntax.RawBinaryOp
import com.processm.processminterpreter.domain.pql.syntax.RawExpression
import com.processm.processminterpreter.domain.pql.syntax.RawFunctionCall
import com.processm.processminterpreter.domain.pql.syntax.RawInList
import com.processm.processminterpreter.domain.pql.syntax.RawLiteral
import com.processm.processminterpreter.domain.pql.syntax.RawQuery
import com.processm.processminterpreter.domain.pql.syntax.RawSelectColumn
import com.processm.processminterpreter.domain.pql.syntax.RawUnaryOp
import com.processm.processminterpreter.domain.pql.catalog.UnaryOperator
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.Type
import com.processm.processminterpreter.domain.pql.common.OrderKey
import com.processm.processminterpreter.domain.pql.resolved.Aggregation
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression
import com.processm.processminterpreter.domain.pql.resolved.ResolvedInList
import com.processm.processminterpreter.domain.pql.resolved.ResolvedQuery
import com.processm.processminterpreter.domain.pql.resolved.ResolvedSelectColumn
import com.processm.processminterpreter.domain.pql.resolved.ScalarFunction
import com.processm.processminterpreter.domain.pql.resolved.TypedBinaryOp
import com.processm.processminterpreter.domain.pql.resolved.TypedUnaryOp

/**
 * Walks a [RawQuery] top-down and produces a [ResolvedQuery] with:
 *  - every attribute resolved through [AttributeResolver]
 *  - literals typed via [LiteralTyper]
 *  - function calls classified as [ScalarFunction] or [Aggregation] via [FunctionCatalog]
 *  - expression result types inferred
 *  - structural shape (expressions, ordering, groupBy) preserved in source order
 *
 * Pure: no Spring, no DB access, deterministic. Takes [ResolutionContext] explicitly
 * so callers can inject classifier lists from whichever source (repository, tests).
 */
class Resolver(
    private val attributes: AttributeResolver = AttributeResolver(),
) {

    fun resolve(raw: RawQuery, ctx: ResolutionContext = ResolutionContext()): ResolvedQuery = when (raw) {
        is RawQuery.Select -> resolveSelect(raw, ctx)
        is RawQuery.Delete -> resolveDelete(raw, ctx)
    }

    private fun resolveSelect(raw: RawQuery.Select, ctx: ResolutionContext): ResolvedQuery.Select {
        val base = raw.from
        return ResolvedQuery.Select(
            from = base,
            columns = raw.columns.map { resolveColumn(it, base, ctx) },
            implicitAll = raw.implicitAll,
            where = raw.where?.let { resolveExpr(it, base, ctx) },
            groupBy = raw.groupBy.map { resolveExpr(it, base, ctx) },
            orderBy = raw.orderBy.map {
                OrderKey(expression = resolveExpr(it.expression, base, ctx), direction = it.direction)
            },
            limit = raw.limit,
            offset = raw.offset,
            location = raw.location,
        )
    }

    private fun resolveDelete(raw: RawQuery.Delete, ctx: ResolutionContext): ResolvedQuery.Delete =
        ResolvedQuery.Delete(
            from = raw.from,
            where = raw.where?.let { resolveExpr(it, raw.from, ctx) },
            location = raw.location,
        )

    private fun resolveColumn(
        col: RawSelectColumn,
        base: Scope,
        ctx: ResolutionContext,
    ): ResolvedSelectColumn =
        ResolvedSelectColumn(
            expression = col.expression?.let { resolveExpr(it, base, ctx) },
            alias = col.alias,
            starAt = col.starAt,
        )

    private fun resolveExpr(expr: RawExpression, base: Scope, ctx: ResolutionContext): ResolvedExpression = when (expr) {
        is RawAttributeRef -> attributes.resolve(expr, defaultScope = base, context = ctx)
        is RawLiteral -> LiteralTyper.type(expr)
        is RawBinaryOp -> TypedBinaryOp(
            op = expr.op,
            left = resolveExpr(expr.left, base, ctx),
            right = resolveExpr(expr.right, base, ctx),
            type = inferBinaryType(expr.op),
            location = expr.location,
        )
        is RawUnaryOp -> {
            val operand = resolveExpr(expr.operand, base, ctx)
            TypedUnaryOp(
                op = expr.op,
                operand = operand,
                type = if (expr.op == UnaryOperator.NOT) Type.BOOLEAN else operand.type,
                location = expr.location,
            )
        }
        is RawFunctionCall -> resolveFunctionCall(expr, base, ctx)
        is RawInList -> ResolvedInList(
            values = expr.values.map { resolveExpr(it, base, ctx) },
            location = expr.location,
        )
    }

    private fun resolveFunctionCall(call: RawFunctionCall, base: Scope, ctx: ResolutionContext): ResolvedExpression {
        val scoped = stripFunctionScope(call.name)
        val args = call.arguments.map { resolveExpr(it, base, ctx) }
        return if (FunctionCatalog.isAggregation(scoped.name)) {
            val argType = args.firstOrNull()?.type ?: Type.UNKNOWN
            val singleArg = args.firstOrNull() ?: error(
                "Aggregation ${scoped.name} at ${call.location} requires at least one argument",
            )
            Aggregation(
                name = scoped.name.lowercase(),
                argument = singleArg,
                type = FunctionCatalog.aggregationReturnType(scoped.name, argType),
                location = call.location,
                scope = scoped.scope,
            )
        } else {
            ScalarFunction(
                name = scoped.name.lowercase(),
                arguments = args,
                scope = scoped.scope,
                type = FunctionCatalog.scalarReturnType(scoped.name) ?: Type.UNKNOWN,
                location = call.location,
            )
        }
    }

    private fun stripFunctionScope(name: String): ScopedFunctionName {
        val idx = name.indexOf(':')
        if (idx <= 0) return ScopedFunctionName(null, name)
        val scope = Scope.tryParse(name.substring(0, idx)) ?: return ScopedFunctionName(null, name)
        return ScopedFunctionName(scope, name.substring(idx + 1))
    }

    private data class ScopedFunctionName(
        val scope: Scope?,
        val name: String,
    )

    private fun inferBinaryType(op: BinaryOperator): Type = when (op) {
        BinaryOperator.EQ, BinaryOperator.NEQ,
        BinaryOperator.LT, BinaryOperator.LTE, BinaryOperator.GT, BinaryOperator.GTE,
        BinaryOperator.AND, BinaryOperator.OR,
        BinaryOperator.LIKE, BinaryOperator.NOT_LIKE, BinaryOperator.MATCHES_REGEX,
        BinaryOperator.IS, BinaryOperator.IS_NOT,
        BinaryOperator.IN, BinaryOperator.NOT_IN,
        -> Type.BOOLEAN

        BinaryOperator.PLUS, BinaryOperator.MINUS,
        BinaryOperator.MUL, BinaryOperator.DIV,
        -> Type.NUMBER
    }
}
