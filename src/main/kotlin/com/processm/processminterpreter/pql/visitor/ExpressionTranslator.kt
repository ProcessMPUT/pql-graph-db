package com.processm.processminterpreter.pql.visitor

import com.processm.processminterpreter.pql.StandardAttributeMapper.Scope
import com.processm.processminterpreter.pql.model.Attribute
import com.processm.processminterpreter.pql.model.BooleanLiteral
import com.processm.processminterpreter.pql.model.DateTimeLiteral
import com.processm.processminterpreter.pql.model.Function
import com.processm.processminterpreter.pql.model.IExpression
import com.processm.processminterpreter.pql.model.NullLiteral
import com.processm.processminterpreter.pql.model.NumberLiteral
import com.processm.processminterpreter.pql.model.StringLiteral

/**
 * Translates PQL expression trees to Cypher string fragments.
 *
 * Handles binary/unary operators, literals, function calls, attributes,
 * and IN-list expressions. Parameterizes all user-supplied values.
 *
 * @param ctx shared translation state (parameters, counters, scope info)
 * @param attributeResolver resolves an Attribute + node label + scope to a Cypher field reference
 */
class ExpressionTranslator(
    private val ctx: CypherTranslationContext,
    private val attributeResolver: (Attribute, String, Scope) -> String,
) {
    /**
     * Translate an IExpression to Cypher string.
     */
    fun translate(expr: IExpression): String =
        when (expr) {
            is BinaryOperator -> translateBinaryOperator(expr)
            is UnaryOperator -> translateUnaryOperator(expr)
            is InListExpression -> translateInListExpression(expr)
            is Attribute -> translateAttribute(expr)
            is Function -> translateFunction(expr)
            is StringLiteral -> translateStringLiteral(expr)
            is NumberLiteral -> translateNumberLiteral(expr)
            is DateTimeLiteral -> translateDateTimeLiteral(expr)
            is BooleanLiteral -> translateBooleanLiteral(expr)
            is NullLiteral -> "null"
            else -> throw IllegalArgumentException("Unknown expression type: ${expr::class.simpleName}")
        }

    /**
     * Translates a SELECT expression to Cypher, converting datetime subtraction between
     * aggregation functions to duration.between() since Neo4j cannot subtract LocalDateTime.
     */
    fun translateSelectExpression(expr: IExpression): String {
        if (expr is BinaryOperator && expr.operator == "-" &&
                isTemporalAggExpression(expr.left) && isTemporalAggExpression(expr.right)) {
            val left = translate(expr.left)
            val right = translate(expr.right)
            return "duration.between($right, $left)"
        }
        return translate(expr)
    }

    /**
     * Returns true if an expression is an aggregation function applied to a temporal attribute.
     */
    fun isTemporalAggExpression(expr: IExpression): Boolean {
        if (expr !is Function || !Function.isAggregation(expr.name)) return false
        val inner = expr.children.firstOrNull() as? Attribute ?: return false
        val name = inner.name.lowercase()
        return "timestamp" in name || name == "time" || "date" in name
    }

    private fun translateBinaryOperator(op: BinaryOperator): String {
        val left = translate(op.left)
        val right = translate(op.right)
        val operator = op.operator.uppercase()

        return when (operator) {
            "=", "<>", ">", ">=", "<", "<=", "AND", "OR" -> "($left $operator $right)"
            "!=" -> "($left <> $right)"
            "IN" -> "$left IN $right"
            "NOT IN" -> "NOT ($left IN $right)"
            "LIKE" -> {
                convertLikeParamToRegex(right)
                "$left =~ $right"
            }
            "MATCHES" -> "$left =~ $right"
            "+", "-", "*", "/", "%" -> "($left $operator $right)"
            else -> "($left $operator $right)"
        }
    }

    private fun translateUnaryOperator(op: UnaryOperator): String {
        val operand = translate(op.operand)
        val operator = op.operator.uppercase()

        return when (operator) {
            "NOT" -> "NOT ($operand)"
            "IS NULL" -> "$operand IS NULL"
            "IS NOT NULL" -> "$operand IS NOT NULL"
            "-" -> "-$operand"
            else -> "$operator $operand"
        }
    }

    private fun translateInListExpression(expr: InListExpression): String {
        val rawValues =
            expr.values.map { value ->
                when (value) {
                    is StringLiteral -> value.value
                    is NumberLiteral -> value.value
                    is DateTimeLiteral -> {
                        if (value.value.hour == 0 && value.value.minute == 0 && value.value.second == 0) {
                            value.value.toLocalDate().toString()
                        } else {
                            value.value.toString()
                        }
                    }
                    is BooleanLiteral -> value.value
                    is NullLiteral -> null
                    else -> throw IllegalArgumentException("Unsupported value type in IN list: ${value::class.simpleName}")
                }
            }

        val paramName = ctx.nextParamName()
        ctx.parameters[paramName] = rawValues
        return "\$$paramName"
    }

    private fun translateAttribute(attr: Attribute): String {
        val isHoisted = attr.hoistingPrefix.isNotEmpty()
        // When in EXISTS subquery context for hoisted attributes,
        // use the base scope (where data lives) with the subquery's node variable
        if (isHoisted && ctx.hoistedEventNodeVar != null) {
            val baseScope = mapScope(attr.scope)
            return attributeResolver(attr, ctx.hoistedEventNodeVar!!, baseScope)
        }

        // For hoisted attrs outside EXISTS context (e.g. avg(^^e:total) in SELECT),
        // data lives on the base scope node (event), not the effective scope node (log).
        if (isHoisted) {
            val baseScope = mapScope(attr.scope)
            val nodeLabel = ctx.scopeToNodeLabel(baseScope)
            return attributeResolver(attr, nodeLabel, baseScope)
        }

        val scope = mapScope(attr.effectiveScope ?: com.processm.processminterpreter.pql.model.Scope.Event)
        val nodeLabel = ctx.scopeToNodeLabel(scope)
        return attributeResolver(attr, nodeLabel, scope)
    }

    private fun translateFunction(func: Function): String {
        val funcName = func.name.lowercase()
        val args = func.children.map { translate(it) }

        // Handle date/time extraction functions as property accessors
        val dateProperty = when (funcName) {
            "year" -> "year"
            "month" -> "month"
            "day" -> "day"
            "hour" -> "hour"
            "minute" -> "minute"
            "second" -> "second"
            "dayofweek" -> "dayOfWeek"
            else -> null
        }

        if (dateProperty != null && args.size == 1) {
            if (funcName == "dayofweek") {
                return "(${args[0]}.$dateProperty % 7) + 1"
            }
            return "${args[0]}.$dateProperty"
        }

        val cypherFuncName =
            when (funcName) {
                "count" -> {
                    if (func.children.size == 1) {
                        val child = func.children[0]
                        if (child is Attribute) {
                            when (child.scope) {
                                com.processm.processminterpreter.pql.model.Scope.Log -> return "count(DISTINCT log)"
                                com.processm.processminterpreter.pql.model.Scope.Trace -> return "count(DISTINCT trace)"
                                else -> {}
                            }
                        }
                    }
                    if (args.isNotEmpty()) {
                        val arg = args[0]
                        if (arg.endsWith(".traceId") || arg.endsWith(".logId") || arg.endsWith(".eventId")) {
                            return "count(DISTINCT $arg)"
                        }
                    }
                    "count"
                }
                "sum", "avg", "min", "max" -> funcName
                "now" -> "datetime"
                "lower", "upper", "trim" -> funcName
                "substring" -> "substring"
                "length" -> "size"
                "abs", "ceil", "floor", "round", "sqrt" -> funcName
                else -> funcName
            }

        return if (args.isEmpty()) {
            "$cypherFuncName()"
        } else {
            "$cypherFuncName(${args.joinToString(", ")})"
        }
    }

    private fun translateStringLiteral(lit: StringLiteral): String {
        val paramName = ctx.nextParamName()
        val value =
            if (lit.value.startsWith("D") && lit.value.length >= 10 &&
                lit.value[1].isDigit() && lit.value[5] == '-' && lit.value[8] == '-'
            ) {
                lit.value.substring(1)
            } else {
                lit.value
            }
        ctx.parameters[paramName] = value
        return "\$$paramName"
    }

    private fun translateNumberLiteral(lit: NumberLiteral): String = lit.value.toString()

    private fun translateDateTimeLiteral(lit: DateTimeLiteral): String {
        val paramName = ctx.nextParamName()
        ctx.parameters[paramName] = lit.value.toString()
        return "localdatetime(\$$paramName)"
    }

    private fun translateBooleanLiteral(lit: BooleanLiteral): String = lit.value.toString()

    /**
     * Converts a SQL LIKE parameter value to a regex pattern for Neo4j's =~ operator.
     */
    private fun convertLikeParamToRegex(paramRef: String) {
        val paramName = paramRef.removePrefix("$")
        val value = ctx.parameters[paramName]
        if (value is String) {
            val escaped = value
                .replace("\\", "\\\\")
                .replace(".", "\\.")
                .replace("^", "\\^")
                .replace("$", "\\$")
                .replace("+", "\\+")
                .replace("?", "\\?")
                .replace("{", "\\{")
                .replace("}", "\\}")
                .replace("|", "\\|")
                .replace("(", "\\(")
                .replace(")", "\\)")
                .replace("[", "\\[")
                .replace("]", "\\]")
                .replace("*", "\\*")
            val regex = escaped
                .replace("%", ".*")
                .replace("_", ".")
            ctx.parameters[paramName] = regex
        }
    }

    private fun mapScope(modelScope: com.processm.processminterpreter.pql.model.Scope): Scope =
        when (modelScope) {
            com.processm.processminterpreter.pql.model.Scope.Log -> Scope.Log
            com.processm.processminterpreter.pql.model.Scope.Trace -> Scope.Trace
            com.processm.processminterpreter.pql.model.Scope.Event -> Scope.Event
        }
}
