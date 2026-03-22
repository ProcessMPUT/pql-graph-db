package com.processm.processminterpreter.pql.visitor

import QLParser
import QLParserBaseVisitor
import com.processm.processminterpreter.pql.model.*
import org.antlr.v4.runtime.tree.TerminalNode
import org.slf4j.LoggerFactory

/**
 * ANTLR Visitor that builds a Query object from the parse tree.
 *
 * This visitor walks through the ANTLR parse tree and constructs
 * a complete Query object representing all aspects of the PQL query.
 *
 * The Query object can then be validated and used to generate Cypher.
 *
 * Based on ProcessM QueryListener:
 * https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/main/kotlin/processm/core/querylanguage/Query.kt
 */
class QueryBuilder : QLParserBaseVisitor<Any>() {

    private val logger = LoggerFactory.getLogger(QueryBuilder::class.java)

    /**
     * Build a Query object from the parse tree.
     *
     * @param ctx the query context (root of parse tree)
     * @param queryString the original query string
     * @return parsed Query object
     */
    fun build(ctx: QLParser.QueryContext, queryString: String = ""): Query {
        logger.debug("Building Query from parse tree: $queryString")

        val query = Query.empty(queryString)
        buildInto(ctx, query)

        // Validate the query
        query.validate()

        logger.debug("Query built successfully")
        return query
    }

    /**
     * Build into an existing Query object from the parse tree.
     * Used internally by Query constructor.
     *
     * @param ctx the query context (root of parse tree)
     * @param query the Query object to populate
     */
    fun buildInto(ctx: QLParser.QueryContext, query: Query) {
        logger.debug("Building Query from parse tree: ${query.query}")

        when {
            ctx.read_query() != null -> buildReadQuery(ctx.read_query(), query)
            ctx.delete_query() != null -> buildDeleteQuery(ctx.delete_query(), query)
            else -> throw PQLSyntaxException(-1, -1, "Unknown query type")
        }
    }

    // ========================================
    // READ QUERY (SELECT)
    // ========================================

    /**
     * read_query: select where? group_by? order_by? limit? offset?
     */
    private fun buildReadQuery(ctx: QLParser.Read_queryContext, query: Query) {
        logger.debug("Building read query")

        // SELECT clause
        buildSelectClause(ctx.select(), query)

        // WHERE clause
        ctx.where()?.let { buildWhereClause(it, query) }

        // GROUP BY clause
        ctx.group_by()?.let { buildGroupByClause(it, query) }

        // ORDER BY clause
        ctx.order_by()?.let { buildOrderByClause(it, query) }

        // LIMIT clause
        ctx.limit()?.let { buildLimitClause(it, query) }

        // OFFSET clause
        ctx.offset()?.let { buildOffsetClause(it, query) }
    }

    // ========================================
    // DELETE QUERY
    // ========================================

    /**
     * delete_query: delete where? order_by? limit? offset?
     */
    private fun buildDeleteQuery(ctx: QLParser.Delete_queryContext, query: Query) {
        logger.debug("Building delete query")

        // DELETE clause
        buildDeleteClause(ctx.delete(), query)

        // WHERE clause
        ctx.where()?.let { buildWhereClause(it, query) }

        // ORDER BY clause
        ctx.order_by()?.let { buildOrderByClause(it, query) }

        // LIMIT clause
        ctx.limit()?.let { buildLimitClause(it, query) }

        // OFFSET clause
        ctx.offset()?.let { buildOffsetClause(it, query) }
    }

    // ========================================
    // SELECT CLAUSE
    // ========================================

    /**
     * select: (empty) | SELECT '*' (',' column_list)? | SELECT column_list
     */
    private fun buildSelectClause(ctx: QLParser.SelectContext, query: Query) {
        when (ctx) {
            is QLParser.Select_all_implicitContext -> {
                // Empty SELECT - implicit SELECT *
                Scope.values().forEach { scope ->
                    query.setImplicitSelectAll(scope, true)
                }
                logger.debug("Implicit SELECT * for all scopes")
            }

            is QLParser.Select_allContext -> {
                // SELECT * or SELECT *, ...
                // Select all scopes for explicit SELECT *
                Scope.values().forEach { scope ->
                    query.setSelectAll(scope, true)
                }

                // Process column_list if present
                ctx.column_list()?.let { processColumnList(it, query) }

                logger.debug("Explicit SELECT *")
            }

            is QLParser.Select_column_listContext -> {
                // SELECT column_list
                processColumnList(ctx.column_list(), query)
                logger.debug("SELECT with column list")
            }
        }
    }

    /**
     * Process column_list and add to query.
     *
     * column_list can be:
     * - SCOPE COLON '*' (scoped SELECT *)
     * - arith_expr_root (attribute or expression)
     */
    private fun processColumnList(ctx: QLParser.Column_listContext, query: Query) {
        when (ctx) {
            is QLParser.Scoped_select_allContext -> {
                // e:* or t:* or l:*
                val scopeText = ctx.SCOPE()?.text ?: "e"
                val scope = Scope.parse(scopeText)
                query.setSelectAll(scope, true)

                // Recursively process next column_list if present
                ctx.column_list()?.let { processColumnList(it, query) }

                logger.debug("Scoped SELECT * for scope $scope")
            }

            is QLParser.Column_list_arith_expr_rootContext -> {
                // arith_expr (could be attribute, function, or expression)
                val expr = buildArithExpr(ctx.arith_expr_root().arith_expr())

                // Determine scope from expression
                val scope = when (expr) {
                    is Attribute -> expr.scope
                    is com.processm.processminterpreter.pql.model.Function -> expr.scope ?: expr.effectiveScope ?: Scope.Event
                    is Expression -> expr.effectiveScope ?: Scope.Event
                    else -> Scope.Event
                }

                // Add to appropriate collection
                if (expr is Attribute) {
                    query.addSelectAttribute(expr)
                } else {
                    query.addSelectExpression(expr, scope)
                }

                // Recursively process next column_list if present
                ctx.column_list()?.let { processColumnList(it, query) }

                logger.debug("Added SELECT expression: $expr with scope $scope")
            }
        }
    }

    // ========================================
    // DELETE CLAUSE
    // ========================================

    /**
     * delete: DELETE SCOPE?
     */
    private fun buildDeleteClause(ctx: QLParser.DeleteContext, query: Query) {
        val scopeText = ctx.SCOPE()?.text ?: "e" // Default to EVENT
        val scope = Scope.parse(scopeText)

        query.deleteScope = scope
        // DELETE sets implicit SELECT ALL for all scopes (ProcessM behavior)
        Scope.values().forEach { s ->
            query.setImplicitSelectAll(s, true)
        }
        logger.debug("DELETE scope: $scope")
    }

    // ========================================
    // WHERE CLAUSE
    // ========================================

    /**
     * where: WHERE logic_expr
     */
    private fun buildWhereClause(ctx: QLParser.WhereContext, query: Query) {
        val expr = buildLogicExpr(ctx.logic_expr())
        query.whereExpression = expr as Expression
        logger.debug("WHERE expression built")
    }

    /**
     * Build a logic expression (AND, OR, NOT, comparisons, etc.)
     */
    private fun buildLogicExpr(ctx: QLParser.Logic_exprContext): IExpression {
        val line = ctx.start.line
        val charPos = ctx.start.charPositionInLine

        // Get the text to identify which alternative
        val text = ctx.text

        return when {
            // Parentheses: (logic_expr)
            ctx.childCount == 3 && ctx.getChild(0).text == "(" -> {
                buildLogicExpr(ctx.logic_expr(0))
            }

            // Binary logic operators: AND, OR
            ctx.OP_AND() != null -> {
                BinaryOperator(
                    "AND",
                    buildLogicExpr(ctx.logic_expr(0)),
                    buildLogicExpr(ctx.logic_expr(1)),
                    line,
                    charPos,
                )
            }

            ctx.OP_OR() != null -> {
                BinaryOperator(
                    "OR",
                    buildLogicExpr(ctx.logic_expr(0)),
                    buildLogicExpr(ctx.logic_expr(1)),
                    line,
                    charPos,
                )
            }

            // Unary NOT
            ctx.OP_NOT() != null -> {
                UnaryOperator(
                    "NOT",
                    buildLogicExpr(ctx.logic_expr(0)),
                    line,
                    charPos,
                )
            }

            // Comparison operators: <, <=, =, !=, >, >=
            ctx.OP_LT() != null || ctx.OP_LE() != null || ctx.OP_EQ() != null ||
                ctx.OP_NEQ() != null || ctx.OP_GT() != null || ctx.OP_GE() != null -> {
                val op = when {
                    ctx.OP_LT() != null -> "<"
                    ctx.OP_LE() != null -> "<="
                    ctx.OP_EQ() != null -> "="
                    ctx.OP_NEQ() != null -> "!="
                    ctx.OP_GT() != null -> ">"
                    ctx.OP_GE() != null -> ">="
                    else -> "="
                }
                BinaryOperator(
                    op,
                    buildArithExpr(ctx.arith_expr(0)),
                    buildArithExpr(ctx.arith_expr(1)),
                    line,
                    charPos,
                )
            }

            // IS NULL, IS NOT NULL
            ctx.OP_IS_NULL() != null -> {
                UnaryOperator(
                    "IS NULL",
                    buildArithExpr(ctx.arith_expr(0)),
                    line,
                    charPos,
                )
            }

            ctx.OP_IS_NOT_NULL() != null -> {
                UnaryOperator(
                    "IS NOT NULL",
                    buildArithExpr(ctx.arith_expr(0)),
                    line,
                    charPos,
                )
            }

            // IN, NOT IN
            ctx.OP_IN() != null -> {
                BinaryOperator(
                    "IN",
                    buildArithExpr(ctx.arith_expr(0)),
                    buildInList(ctx.in_list()),
                    line,
                    charPos,
                )
            }

            ctx.OP_NOT_IN() != null -> {
                BinaryOperator(
                    "NOT IN",
                    buildArithExpr(ctx.arith_expr(0)),
                    buildInList(ctx.in_list()),
                    line,
                    charPos,
                )
            }

            // MATCHES, LIKE
            ctx.OP_MATCHES() != null -> {
                BinaryOperator(
                    "MATCHES",
                    buildArithExpr(ctx.arith_expr(0)),
                    StringLiteral.parse(ctx.STRING().text, line, charPos),
                    line,
                    charPos,
                )
            }

            ctx.OP_LIKE() != null -> {
                BinaryOperator(
                    "LIKE",
                    buildArithExpr(ctx.arith_expr(0)),
                    StringLiteral.parse(ctx.STRING().text, line, charPos),
                    line,
                    charPos,
                )
            }

            else -> {
                throw PQLSyntaxException(line, charPos, "Unknown logic expression: $text")
            }
        }
    }

    /**
     * Build IN list: (value1, value2, ...)
     */
    private fun buildInList(ctx: QLParser.In_listContext): IExpression {
        val line = ctx.start.line
        val charPos = ctx.start.charPositionInLine

        val values = mutableListOf<IExpression>()

        // Process each child - can be ID (TerminalNode) or scalar (RuleContext)
        ctx.id_or_scalar_list().children.forEach { child ->
            when (child) {
                is TerminalNode -> {
                    // Terminal node - check if it's ID (not comma)
                    if (child.symbol.type == QLParser.ID) {
                        values.add(Attribute(child.text, line, charPos))
                    }
                    // Ignore commas
                }
                is QLParser.ScalarContext -> {
                    // RuleContext for scalar
                    values.add(buildScalarFromContext(child))
                }
            }
        }

        return InListExpression(values, line, charPos)
    }

    // ========================================
    // ARITHMETIC EXPRESSIONS
    // ========================================

    /**
     * Build an arithmetic expression (can be attribute, literal, function, or operator).
     */
    private fun buildArithExpr(ctx: QLParser.Arith_exprContext): IExpression {
        val line = ctx.start.line
        val charPos = ctx.start.charPositionInLine

        return when {
            // Parentheses: (arith_expr)
            ctx.childCount == 3 && ctx.getChild(0).text == "(" -> {
                buildArithExpr(ctx.arith_expr(0))
            }

            // Binary arithmetic: +, -, *, /
            ctx.childCount == 3 && ctx.getChild(1).text in listOf("+", "-", "*", "/") -> {
                val op = ctx.getChild(1).text
                // Use operator token's position (matches ProcessM behavior)
                val opToken = (ctx.getChild(1) as? org.antlr.v4.runtime.tree.TerminalNode)?.symbol
                val opLine = opToken?.line ?: line
                val opCharPos = opToken?.charPositionInLine ?: charPos
                BinaryOperator(
                    op,
                    buildArithExpr(ctx.arith_expr(0)),
                    buildArithExpr(ctx.arith_expr(1)),
                    opLine,
                    opCharPos,
                )
            }

            // Function call
            ctx.func() != null -> {
                buildFunction(ctx.func())
            }

            // Attribute (ID)
            ctx.ID() != null -> {
                Attribute(ctx.ID().text, line, charPos)
            }

            // Scalar literal
            ctx.scalar() != null -> {
                buildScalarFromContext(ctx.scalar())
            }

            else -> {
                throw PQLSyntaxException(line, charPos, "Unknown arithmetic expression: ${ctx.text}")
            }
        }
    }

    /**
     * Build a function call.
     */
    private fun buildFunction(ctx: QLParser.FuncContext): com.processm.processminterpreter.pql.model.Function {
        val line = ctx.start.line
        val charPos = ctx.start.charPositionInLine

        return when {
            // FUNC_SCALAR0: now()
            ctx.FUNC_SCALAR0() != null -> {
                val funcName = ctx.FUNC_SCALAR0().text
                com.processm.processminterpreter.pql.model.Function(funcName, line, charPos)
            }

            // FUNC_SCALAR1: year(expr), upper(expr), etc.
            ctx.FUNC_SCALAR1() != null -> {
                val funcName = ctx.FUNC_SCALAR1().text
                val arg = buildArithExpr(ctx.arith_expr())
                com.processm.processminterpreter.pql.model.Function(funcName, line, charPos, args = arrayOf(arg))
            }

            // FUNC_AGGR: count(id), sum(id), etc.
            ctx.FUNC_AGGR() != null -> {
                val funcName = ctx.FUNC_AGGR().text
                val argName = ctx.ID().text
                val arg = Attribute(argName, line, charPos)
                com.processm.processminterpreter.pql.model.Function(funcName, line, charPos, args = arrayOf(arg))
            }

            else -> {
                throw PQLSyntaxException(line, charPos, "Unknown function: ${ctx.text}")
            }
        }
    }

    /**
     * Build a scalar literal from context.
     */
    private fun buildScalarFromContext(ctx: QLParser.ScalarContext): Literal<*> {
        val line = ctx.start.line
        val charPos = ctx.start.charPositionInLine

        return when {
            ctx.STRING() != null -> StringLiteral.parse(ctx.STRING().text, line, charPos)
            ctx.NUMBER() != null -> NumberLiteral.parse(ctx.NUMBER().text, line, charPos)
            ctx.BOOLEAN() != null -> BooleanLiteral.parse(ctx.BOOLEAN().text, line, charPos)
            ctx.DATETIME() != null -> DateTimeLiteral.parse(ctx.DATETIME().text, line, charPos)
            ctx.UUID() != null -> UUIDLiteral.parse(ctx.UUID().text, line, charPos)
            ctx.NULL() != null -> NullLiteral.parse(ctx.NULL().text, line, charPos)
            else -> throw PQLSyntaxException(line, charPos, "Unknown scalar type: ${ctx.text}")
        }
    }

    /**
     * Build a scalar literal from text.
     */
    private fun buildScalar(text: String, line: Int, charPos: Int): Literal<*> {
        return when {
            text.startsWith("\"") || text.startsWith("'") -> StringLiteral.parse(text, line, charPos)
            text.equals("true", ignoreCase = true) || text.equals("false", ignoreCase = true) ->
                BooleanLiteral.parse(text, line, charPos)
            text.equals("null", ignoreCase = true) -> NullLiteral.parse(text, line, charPos)
            text.startsWith("D") || text.startsWith("d") -> DateTimeLiteral.parse(text, line, charPos)
            text.contains("-") && text.length > 10 -> UUIDLiteral.parse(text, line, charPos)
            else -> NumberLiteral.parse(text, line, charPos)
        }
    }

    // ========================================
    // GROUP BY CLAUSE
    // ========================================

    /**
     * group_by: GROUP_BY id_list
     */
    private fun buildGroupByClause(ctx: QLParser.Group_byContext, query: Query) {
        ctx.id_list().ID().forEach { idNode ->
            val attrName = idNode.text
            val line = idNode.symbol.line
            val charPos = idNode.symbol.charPositionInLine

            val attr = Attribute(attrName, line, charPos)
            query.addGroupByAttribute(attr)

            logger.debug("Added GROUP BY attribute: $attr")
        }
    }

    // ========================================
    // ORDER BY CLAUSE
    // ========================================

    /**
     * order_by: ORDER_BY column_list_with_order
     */
    private fun buildOrderByClause(ctx: QLParser.Order_byContext, query: Query) {
        ctx.column_list_with_order().ordered_expression_root().forEach { orderedExprCtx ->
            val expr = buildArithExpr(orderedExprCtx.arith_expr())

            val direction = when {
                orderedExprCtx.order_dir().ORDER_ASC() != null -> OrderDirection.Ascending
                orderedExprCtx.order_dir().ORDER_DESC() != null -> OrderDirection.Descending
                else -> OrderDirection.Ascending // Default
            }

            val scope = when (expr) {
                is Attribute -> expr.scope
                is com.processm.processminterpreter.pql.model.Function -> expr.scope ?: expr.effectiveScope ?: Scope.Event
                is Expression -> expr.effectiveScope ?: Scope.Event
                else -> Scope.Event
            }

            query.addOrderByExpression(expr, direction, scope)

            logger.debug("Added ORDER BY: $expr $direction for scope $scope")
        }
    }

    // ========================================
    // LIMIT & OFFSET CLAUSES
    // ========================================

    /**
     * limit: LIMIT limit_number (',' limit_number)*
     */
    private fun buildLimitClause(ctx: QLParser.LimitContext, query: Query) {
        val isSingleLimit = ctx.limit_number().size == 1
        val seenScopes = mutableSetOf<Scope>()
        ctx.limit_number().forEachIndexed { index, limitCtx ->
            val text = limitCtx.NUMBER().text
            val line = limitCtx.NUMBER().symbol.line
            val charPos = limitCtx.NUMBER().symbol.charPositionInLine
            val (scope, value, hasDecimal) = parseScopedNumber(text, index)

            // Single-scope limit with value 0 is rejected (e.g., "limit l:0")
            // Multi-scope limit allows 0 (e.g., "limit l:1, t:2, e:0")
            if (isSingleLimit && value == 0L) {
                throw PQLSyntaxException(
                    PQLSyntaxException.Problem.PositiveIntegerRequired,
                    line, charPos, text
                )
            }

            if (scope != null) {
                // Emit warning if decimal part was truncated (ProcessM behavior)
                if (hasDecimal) {
                    query.emitWarning(
                        PQLSyntaxException(
                            PQLSyntaxException.Problem.DecimalPartDropped,
                            line, charPos, text
                        ),
                    )
                }
                // Check for duplicate scope
                if (scope in seenScopes) {
                    query.emitWarning(
                        PQLSyntaxException(
                            PQLSyntaxException.Problem.DuplicateLimit,
                            line, charPos, scope.toString()
                        ),
                    )
                }
                seenScopes.add(scope)
                query.setLimit(scope, value)
                logger.debug("LIMIT $scope: $value")
            }
        }
    }

    /**
     * offset: OFFSET offset_number (',' offset_number)*
     */
    private fun buildOffsetClause(ctx: QLParser.OffsetContext, query: Query) {
        val seenScopes = mutableSetOf<Scope>()
        ctx.offset_number().forEachIndexed { index, offsetCtx ->
            val text = offsetCtx.NUMBER().text
            val line = offsetCtx.NUMBER().symbol.line
            val charPos = offsetCtx.NUMBER().symbol.charPositionInLine
            val (scope, value, hasDecimal) = parseScopedNumber(text, index)

            // Offset 0 is always rejected (offset 0 = no offset = meaningless)
            if (value == 0L) {
                throw PQLSyntaxException(
                    PQLSyntaxException.Problem.PositiveIntegerRequired,
                    line, charPos, text
                )
            }

            if (scope != null) {
                // Emit warning if decimal part was truncated (ProcessM behavior)
                if (hasDecimal) {
                    query.emitWarning(
                        PQLSyntaxException(
                            PQLSyntaxException.Problem.DecimalPartDropped,
                            line, charPos, text
                        ),
                    )
                }
                // Check for duplicate scope
                if (scope in seenScopes) {
                    query.emitWarning(
                        PQLSyntaxException(
                            PQLSyntaxException.Problem.DuplicateOffset,
                            line, charPos, scope.toString()
                        ),
                    )
                }
                seenScopes.add(scope)
                query.setOffset(scope, value)
                logger.debug("OFFSET $scope: $value")
            }
        }
    }

    /**
     * Result of parsing a scoped number.
     *
     * @property scope The parsed scope (null if unknown)
     * @property value The parsed long value
     * @property hasDecimal True if the value had a decimal part that was truncated
     */
    private data class ScopedNumberResult(val scope: Scope?, val value: Long, val hasDecimal: Boolean)

    /**
     * Helper to parse a number that might have a scope prefix (e.g., "l:5", "trace:10")
     * or be a plain number (positional).
     *
     * ProcessM behavior: If the number has a decimal part, it emits a warning
     * (DecimalPartDropped) and truncates to integer.
     *
     * @param text The token text
     * @param index The position index (for backward compatibility)
     * @return ScopedNumberResult containing scope, value, and whether decimal was dropped
     */
    private fun parseScopedNumber(text: String, index: Int): ScopedNumberResult {
        return if (text.contains(":")) {
            // Scoped syntax: "l:5", "log:5", "trace:10"
            val parts = text.split(":")
            val scopeStr = parts[0]
            val valueStr = parts[1]
            val scope = Scope.parse(scopeStr)
            val doubleValue = valueStr.toDouble()
            // ProcessM uses round() for mathematical rounding, not truncation
            val roundedValue = kotlin.math.round(doubleValue).toLong()
            val hasDecimal = doubleValue != roundedValue.toDouble()

            // Validate non-negative (zero allowed here, checked by caller for context)
            if (roundedValue < 0) {
                throw PQLSyntaxException(
                    PQLSyntaxException.Problem.PositiveIntegerRequired,
                    -1, -1, text
                )
            }

            ScopedNumberResult(scope, roundedValue, hasDecimal)
        } else {
            // No scope prefix - ProcessM requires explicit scope for LIMIT/OFFSET
            throw PQLSyntaxException(
                PQLSyntaxException.Problem.ScopeRequired,
                -1, -1, text
            )
        }
    }
}

// ========================================
// HELPER EXPRESSION CLASSES
// ========================================

/**
 * Binary operator expression (AND, OR, +, -, *, /, <, >, =, etc.)
 */
class BinaryOperator(
    val operator: String,
    val left: IExpression,
    val right: IExpression,
    line: Int = -1,
    charPositionInLine: Int = -1,
) : Expression(line, charPositionInLine, left, right) {

    /**
     * Operator precedence levels (higher number = lower precedence = binds less tightly).
     * ProcessM uses parentheses to show when a lower-precedence operator is nested
     * inside a higher-precedence operator.
     */
    private val precedence: Int
        get() = when (operator.lowercase()) {
            "*", "/" -> 1
            "+", "-" -> 2
            "=", "!=", "<", "<=", ">", ">=", "like", "matches", "in", "not in" -> 3
            "and" -> 4
            "or" -> 5
            else -> 10
        }

    /**
     * ProcessM format: wraps child in parentheses if it has lower precedence.
     * Special case for IN: "left in (values)" with comma-no-space between values.
     */
    override fun toString(): String {
        val op = operator.lowercase()
        return when (op) {
            "in", "not in" -> "${wrapIfNeeded(left)} $op ${formatInList(right)}"
            else -> "${wrapIfNeeded(left)} $op ${wrapIfNeeded(right)}"
        }
    }

    /**
     * Wrap expression in parentheses if it's a BinaryOperator with lower precedence
     * (higher precedence number) than this operator.
     */
    private fun wrapIfNeeded(expr: IExpression): String {
        return if (expr is BinaryOperator && expr.precedence > this.precedence) {
            "(${expr})"
        } else {
            expr.toString()
        }
    }

    private fun formatInList(expr: IExpression): String {
        return when (expr) {
            is InListExpression -> "(${expr.values.joinToString(",")})"
            else -> expr.toString()
        }
    }
}

/**
 * Unary operator expression (NOT, IS NULL, IS NOT NULL, etc.)
 */
class UnaryOperator(
    val operator: String,
    val operand: IExpression,
    line: Int = -1,
    charPositionInLine: Int = -1,
) : Expression(line, charPositionInLine, operand) {

    /**
     * ProcessM format:
     * - NOT: "not (operand)" - prefix with space
     * - IS NULL: "operand is null" - postfix
     * - IS NOT NULL: "operand is not null" - postfix
     */
    override fun toString(): String {
        val op = operator.lowercase()
        return when (op) {
            "not" -> "not ($operand)"
            "is null", "is not null" -> "$operand $op"
            else -> "$op $operand"
        }
    }
}

/**
 * IN list expression for IN/NOT IN operators.
 */
class InListExpression(
    val values: List<IExpression>,
    line: Int = -1,
    charPositionInLine: Int = -1,
) : Expression(line, charPositionInLine, *values.toTypedArray()) {

    /**
     * ProcessM format: "(value1,value2,value3)" - no spaces after commas
     */
    override fun toString(): String = "(${values.joinToString(",")})"
}
