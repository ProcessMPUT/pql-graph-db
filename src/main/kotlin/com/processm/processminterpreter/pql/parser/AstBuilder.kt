package com.processm.processminterpreter.pql.parser

import QLParser
import com.processm.processminterpreter.pql.catalog.BinaryOperator
import com.processm.processminterpreter.pql.catalog.OrderDirection
import com.processm.processminterpreter.pql.ast.AttributeReferenceParser
import com.processm.processminterpreter.pql.ast.PqlExpression
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import com.processm.processminterpreter.pql.common.HierarchicalOffsets
import com.processm.processminterpreter.pql.ast.PqlQuery
import com.processm.processminterpreter.pql.catalog.UnaryOperator
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.error.PQLSyntaxException
import com.processm.processminterpreter.pql.error.Problem
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.TerminalNode
import org.springframework.stereotype.Component

/**
 * Translates an ANTLR parse tree (rooted at [QLParser.QueryContext]) into a domain
 * [PqlQuery]. Pure structural transformation — no scope resolution, no type inference,
 * no catalog lookups. Emits the smallest AST that preserves lexical intent.
 *
 * Key conventions:
 *  - SELECT without an explicit FROM defaults to [Scope.EVENT] (ProcessM convention).
 *  - `SELECT *` (explicit or implicit) expands to three star-columns, one per scope.
 *  - `IS NULL` / `IS NOT NULL` become `BinaryOp(IS|IS_NOT, x, NullLiteral)`.
 *  - `IN (...)` right-hand side uses [PqlExpression.InList].
 */
@Component
class AstBuilder {

    fun build(ctx: QLParser.QueryContext): PqlQuery {
        val loc = ctx.loc()
        return when {
            ctx.read_query() != null -> buildRead(ctx.read_query(), loc)
            ctx.delete_query() != null -> buildDelete(ctx.delete_query(), loc)
            else -> throw PQLSyntaxException(loc, "Unrecognised top-level query form")
        }
    }

    // ---------- SELECT ----------

    private fun buildRead(ctx: QLParser.Read_queryContext, loc: SourceLocation): PqlQuery.Select {
        val select = ctx.select()
        val columns = buildSelect(select)
        val where = ctx.where()?.let { buildLogicExpr(it.logic_expr()) }
        val groupBy = ctx.group_by()?.let { buildGroupBy(it) } ?: emptyList()
        val orderBy = ctx.order_by()?.let { buildOrderBy(it) } ?: emptyList()
        val limit = ctx.limit()?.let { buildLimit(it) } ?: HierarchicalLimits()
        val offset = ctx.offset()?.let { buildOffset(it) } ?: HierarchicalOffsets()
        return PqlQuery.Select(
            from = Scope.EVENT,
            columns = columns,
            implicitAll = select is QLParser.Select_all_implicitContext,
            where = where,
            groupBy = groupBy, orderBy = orderBy,
            limit = limit, offset = offset,
            location = loc,
        )
    }

    private fun buildSelect(ctx: QLParser.SelectContext): List<PqlQuery.SelectColumn> = when (ctx) {
        is QLParser.Select_all_implicitContext -> allScopeStars()
        is QLParser.Select_allContext -> allScopeStars() + (ctx.column_list()?.let { collectColumns(it) } ?: emptyList())
        is QLParser.Select_column_listContext -> collectColumns(ctx.column_list())
        else -> throw PQLSyntaxException(ctx.loc(), "Unknown SELECT alternative")
    }

    private fun allScopeStars(): List<PqlQuery.SelectColumn> =
        Scope.entries.map { PqlQuery.SelectColumn(expression = null, starAt = it) }

    private fun collectColumns(ctx: QLParser.Column_listContext): List<PqlQuery.SelectColumn> {
        val out = mutableListOf<PqlQuery.SelectColumn>()
        var cur: QLParser.Column_listContext? = ctx
        while (cur != null) {
            when (cur) {
                is QLParser.Scoped_select_allContext -> {
                    val scope = Scope.parse(cur.SCOPE().text)
                    out += PqlQuery.SelectColumn(expression = null, starAt = scope)
                    cur = cur.column_list()
                }
                is QLParser.Column_list_arith_expr_rootContext -> {
                    val expr = buildArithExpr(cur.arith_expr_root().arith_expr())
                    out += PqlQuery.SelectColumn(expression = expr)
                    cur = cur.column_list()
                }
                else -> throw PQLSyntaxException(cur.loc(), "Unknown column_list alternative")
            }
        }
        return out
    }

    // ---------- DELETE ----------

    private fun buildDelete(ctx: QLParser.Delete_queryContext, loc: SourceLocation): PqlQuery.Delete {
        val scopeTok = ctx.delete().SCOPE()?.text
        val scope = scopeTok?.let { Scope.parse(it) } ?: Scope.EVENT
        val where = ctx.where()?.let { buildLogicExpr(it.logic_expr()) }
        return PqlQuery.Delete(from = scope, where = where, location = loc)
    }

    // ---------- WHERE / logic_expr ----------

    private fun buildLogicExpr(ctx: QLParser.Logic_exprContext): PqlExpression {
        val loc = ctx.loc()

        // (logic_expr)
        if (ctx.childCount == 3 && ctx.getChild(0).text == "(" && ctx.logic_expr().size == 1) {
            return buildLogicExpr(ctx.logic_expr(0))
        }
        // AND / OR
        ctx.OP_AND()?.let {
            return PqlExpression.Binary(BinaryOperator.AND, buildLogicExpr(ctx.logic_expr(0)), buildLogicExpr(ctx.logic_expr(1)), location = loc)
        }
        ctx.OP_OR()?.let {
            return PqlExpression.Binary(BinaryOperator.OR, buildLogicExpr(ctx.logic_expr(0)), buildLogicExpr(ctx.logic_expr(1)), location = loc)
        }
        // NOT
        ctx.OP_NOT()?.let {
            return PqlExpression.Unary(UnaryOperator.NOT, buildLogicExpr(ctx.logic_expr(0)), location = loc)
        }
        // Comparisons
        comparisonOp(ctx)?.let { op ->
            return PqlExpression.Binary(op, buildArithExpr(ctx.arith_expr(0)), buildArithExpr(ctx.arith_expr(1)), location = loc)
        }
        // IS NULL / IS NOT NULL
        ctx.OP_IS_NULL()?.let {
            val nullLit = PqlExpression.Literal("null", PqlExpression.LiteralKind.NULL, location = loc)
            return PqlExpression.Binary(BinaryOperator.IS, buildArithExpr(ctx.arith_expr(0)), nullLit, location = loc)
        }
        ctx.OP_IS_NOT_NULL()?.let {
            val nullLit = PqlExpression.Literal("null", PqlExpression.LiteralKind.NULL, location = loc)
            return PqlExpression.Binary(BinaryOperator.IS_NOT, buildArithExpr(ctx.arith_expr(0)), nullLit, location = loc)
        }
        // IN / NOT IN
        ctx.OP_IN()?.let {
            return PqlExpression.Binary(BinaryOperator.IN, buildArithExpr(ctx.arith_expr(0)), buildInList(ctx.in_list()), location = loc)
        }
        ctx.OP_NOT_IN()?.let {
            return PqlExpression.Binary(BinaryOperator.NOT_IN, buildArithExpr(ctx.arith_expr(0)), buildInList(ctx.in_list()), location = loc)
        }
        // MATCHES / LIKE
        ctx.OP_MATCHES()?.let {
            val str = stringLiteralFromToken(ctx.STRING(), loc)
            return PqlExpression.Binary(BinaryOperator.MATCHES_REGEX, buildArithExpr(ctx.arith_expr(0)), str, location = loc)
        }
        ctx.OP_LIKE()?.let {
            val str = stringLiteralFromToken(ctx.STRING(), loc)
            return PqlExpression.Binary(BinaryOperator.LIKE, buildArithExpr(ctx.arith_expr(0)), str, location = loc)
        }
        throw PQLSyntaxException(loc, "Unknown logic expression: ${ctx.text}")
    }

    private fun comparisonOp(ctx: QLParser.Logic_exprContext): BinaryOperator? = when {
        ctx.OP_EQ() != null -> BinaryOperator.EQ
        ctx.OP_NEQ() != null -> BinaryOperator.NEQ
        ctx.OP_LT() != null -> BinaryOperator.LT
        ctx.OP_LE() != null -> BinaryOperator.LTE
        ctx.OP_GT() != null -> BinaryOperator.GT
        ctx.OP_GE() != null -> BinaryOperator.GTE
        else -> null
    }

    private fun buildInList(ctx: QLParser.In_listContext): PqlExpression.InList {
        val loc = ctx.loc()
        val values = mutableListOf<PqlExpression>()
        ctx.id_or_scalar_list().children.forEach { child ->
            when (child) {
                is TerminalNode -> {
                    if (child.symbol.type == QLParser.ID) {
                        values += AttributeReferenceParser.parse(child.text, child.symbol.loc())
                    }
                    // ignore commas
                }
                is QLParser.ScalarContext -> values += buildScalar(child)
            }
        }
        return PqlExpression.InList(values, loc)
    }

    // ---------- arith_expr ----------

    private fun buildArithExpr(ctx: QLParser.Arith_exprContext): PqlExpression {
        // parens
        if (ctx.childCount == 3 && ctx.getChild(0).text == "(" && ctx.arith_expr().size == 1) {
            return buildArithExpr(ctx.arith_expr(0))
        }
        // binary arithmetic
        if (ctx.childCount == 3) {
            val opText = ctx.getChild(1).text
            val op = when (opText) {
                "+" -> BinaryOperator.PLUS
                "-" -> BinaryOperator.MINUS
                "*" -> BinaryOperator.MUL
                "/" -> BinaryOperator.DIV
                else -> null
            }
            if (op != null) {
                val opTok = (ctx.getChild(1) as? TerminalNode)?.symbol
                val opLoc = opTok?.loc() ?: ctx.loc()
                return PqlExpression.Binary(op, buildArithExpr(ctx.arith_expr(0)), buildArithExpr(ctx.arith_expr(1)), location = opLoc)
            }
        }
        ctx.func()?.let { return buildFunction(it) }
        ctx.ID()?.let { return AttributeReferenceParser.parse(it.text, it.symbol.loc()) }
        ctx.scalar()?.let { return buildScalar(it) }
        throw PQLSyntaxException(ctx.loc(), "Unknown arithmetic expression: ${ctx.text}")
    }

    private fun buildFunction(ctx: QLParser.FuncContext): PqlExpression.Call {
        val loc = ctx.loc()
        return when {
            ctx.FUNC_SCALAR0() != null -> PqlExpression.Call(ctx.FUNC_SCALAR0().text, emptyList(), location = loc)
            ctx.FUNC_SCALAR1() != null ->
                PqlExpression.Call(ctx.FUNC_SCALAR1().text, listOf(buildArithExpr(ctx.arith_expr())), location = loc)
            ctx.FUNC_AGGR() != null -> {
                val idTok = ctx.ID()
                val arg = AttributeReferenceParser.parse(idTok.text, idTok.symbol.loc())
                PqlExpression.Call(ctx.FUNC_AGGR().text, listOf(arg), location = loc)
            }
            else -> throw PQLSyntaxException(loc, "Unknown function: ${ctx.text}")
        }
    }

    // ---------- literals ----------

    private fun buildScalar(ctx: QLParser.ScalarContext): PqlExpression.Literal {
        val loc = ctx.loc()
        return when {
            ctx.STRING() != null -> PqlExpression.Literal(ctx.STRING().text, PqlExpression.LiteralKind.STRING, location = loc)
            ctx.NUMBER() != null -> PqlExpression.Literal(ctx.NUMBER().text, PqlExpression.LiteralKind.NUMBER, location = loc)
            ctx.BOOLEAN() != null -> PqlExpression.Literal(ctx.BOOLEAN().text, PqlExpression.LiteralKind.BOOLEAN, location = loc)
            ctx.DATETIME() != null -> PqlExpression.Literal(ctx.DATETIME().text, PqlExpression.LiteralKind.DATETIME, location = loc)
            ctx.UUID() != null -> PqlExpression.Literal(ctx.UUID().text, PqlExpression.LiteralKind.UUID, location = loc)
            ctx.NULL() != null -> PqlExpression.Literal(ctx.NULL().text, PqlExpression.LiteralKind.NULL, location = loc)
            else -> throw PQLSyntaxException(loc, "Unknown scalar: ${ctx.text}")
        }
    }

    private fun stringLiteralFromToken(node: TerminalNode, loc: SourceLocation): PqlExpression.Literal =
        PqlExpression.Literal(node.text, PqlExpression.LiteralKind.STRING, location = node.symbol.loc())
            .copy(location = loc) // prefer parent loc for compound operators

    // ---------- GROUP BY / ORDER BY ----------

    private fun buildGroupBy(ctx: QLParser.Group_byContext): List<PqlExpression> =
        ctx.id_list().ID().map { AttributeReferenceParser.parse(it.text, it.symbol.loc()) }

    private fun buildOrderBy(ctx: QLParser.Order_byContext): List<PqlQuery.OrderKey> =
        ctx.column_list_with_order().ordered_expression_root().map { o ->
            val expr = buildArithExpr(o.arith_expr())
            val dir = when {
                o.order_dir().ORDER_DESC() != null -> OrderDirection.DESC
                else -> OrderDirection.ASC
            }
            PqlQuery.OrderKey(expr, dir)
        }

    // ---------- LIMIT / OFFSET ----------

    private fun buildLimit(ctx: QLParser.LimitContext): HierarchicalLimits {
        val nums = ctx.limit_number()
        var log: Long? = null; var trace: Long? = null; var event: Long? = null
        val seen = mutableSetOf<Scope>()
        nums.forEach { lc ->
            val tok = lc.NUMBER().symbol
            val (scope, value) = parseScopedNumber(tok)
            if (scope in seen) {
                // duplicates are tolerated but last-write-wins, matching ProcessM warning-only semantics
            }
            seen += scope
            when (scope) {
                Scope.LOG -> log = value
                Scope.TRACE -> trace = value
                Scope.EVENT -> event = value
            }
        }
        return HierarchicalLimits(log = log, trace = trace, event = event)
    }

    private fun buildOffset(ctx: QLParser.OffsetContext): HierarchicalOffsets {
        val nums = ctx.offset_number()
        var log: Long? = null; var trace: Long? = null; var event: Long? = null
        val seen = mutableSetOf<Scope>()
        nums.forEach { oc ->
            val tok = oc.NUMBER().symbol
            val (scope, value) = parseScopedNumber(tok)
            seen += scope
            when (scope) {
                Scope.LOG -> log = value
                Scope.TRACE -> trace = value
                Scope.EVENT -> event = value
            }
        }
        return HierarchicalOffsets(log = log, trace = trace, event = event)
    }

    /** Parses `"l:5"` / `"trace:10"` into (Scope, Long). ProcessM requires explicit scope. */
    private fun parseScopedNumber(tok: Token): Pair<Scope, Long> {
        val text = tok.text
        if (!text.contains(":")) {
            throw PQLSyntaxException(Problem.ScopeRequired, tok.loc(), text)
        }
        val parts = text.split(":", limit = 2)
        val scope = Scope.parse(parts[0])
        val d = parts[1].toDouble()
        val rounded = kotlin.math.round(d).toLong()
        if (rounded <= 0) {
            throw PQLSyntaxException(Problem.PositiveIntegerRequired, tok.loc(), text)
        }
        return scope to rounded
    }

    // ---------- helpers ----------

    private fun ParserRuleContext.loc(): SourceLocation =
        SourceLocation(this.start.line, this.start.charPositionInLine)

    private fun Token.loc(): SourceLocation = SourceLocation(this.line, this.charPositionInLine)
}
