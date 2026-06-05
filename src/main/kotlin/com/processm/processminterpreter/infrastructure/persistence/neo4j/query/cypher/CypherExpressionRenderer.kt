package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.infrastructure.persistence.neo4j.property.NestedAttributePathCodec
import com.processm.processminterpreter.domain.pql.catalog.AttributeKind
import com.processm.processminterpreter.domain.pql.catalog.BinaryOperator
import com.processm.processminterpreter.domain.pql.catalog.UnaryOperator
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.domain.pql.catalog.Type
import com.processm.processminterpreter.domain.pql.resolved.Aggregation
import com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression
import com.processm.processminterpreter.domain.pql.resolved.ResolvedInList
import com.processm.processminterpreter.domain.pql.resolved.ScalarFunction
import com.processm.processminterpreter.domain.pql.resolved.TypedBinaryOp
import com.processm.processminterpreter.domain.pql.resolved.TypedLiteral
import com.processm.processminterpreter.domain.pql.resolved.TypedUnaryOp

internal class CypherExpressionRenderer(
    private val propertyMapper: PhysicalAttributeMapper,
    private val catalog: StandardAttributeCatalog = StandardAttributeCatalog,
) {
    fun render(e: ResolvedExpression, s: CypherBuildState): String {
        if (s.canUseExpressionAliases) {
            s.expressionAlias(exprKey(e))?.let { return it }
        }
        return when (e) {
            is ResolvedAttribute -> renderAttribute(e, s)
            is TypedLiteral -> e.value?.let { "\$${s.bindParam(it)}" } ?: "null"
            is TypedBinaryOp -> renderBinaryOp(e, s)
            is TypedUnaryOp -> renderUnaryOp(e, s)
            is ResolvedInList -> "[${e.values.joinToString(", ") { render(it, s) }}]"
            is ScalarFunction -> renderScalarFunction(e, s)
            is Aggregation -> renderAggregation(e, s)
        }
    }

    fun renderWithHoistedEventNode(
        e: ResolvedExpression,
        s: CypherBuildState,
        eventNodeVar: String,
    ): String =
        s.withHoistedEventNodeVar(eventNodeVar) {
            render(e, s)
        }

    private fun renderAttribute(a: ResolvedAttribute, s: CypherBuildState): String {
        if (a.kind != AttributeKind.CLASSIFIER || a.classifierKeys.isEmpty()) {
            return propertyRef(a, s).toCypher()
        }
        return classifierKeyAttributes(a)
            .joinToString(" + ") { "coalesce(toString(${propertyRef(it, s).toCypher()}), '')" }
    }

    fun renderAggregation(e: Aggregation, s: CypherBuildState): String {
        val arg = s.withAggregationArgumentRendering {
            render(e.argument, s)
        }
        return when (e.name.lowercase()) {
            "sum" -> "CASE WHEN count($arg) = 0 THEN null ELSE sum($arg) END"
            "count" -> renderCountAggregation(e.argument, arg)
            else -> "${e.name}($arg)"
        }
    }

    fun renderTemporalDifferenceInDays(
        later: ResolvedExpression,
        earlier: ResolvedExpression,
        s: CypherBuildState,
    ): String {
        val laterRendered = render(later, s)
        val earlierRendered = render(earlier, s)
        return "toFloat(duration.inSeconds($earlierRendered, $laterRendered).seconds) / 86400.0"
    }

    private fun renderCountAggregation(argument: ResolvedExpression, renderedArgument: String): String {
        val attribute = argument as? ResolvedAttribute ?: return "count($renderedArgument)"
        if (attribute.baseScope == Scope.EVENT) return "count($renderedArgument)"

        val component = nodeVarFor(attribute.baseScope)
        return "count(DISTINCT CASE WHEN $renderedArgument IS NULL THEN null ELSE $component END)"
    }

    fun propertyRef(a: ResolvedAttribute): PropertyRef =
        propertyRef(a, state = null)

    fun classifierKeyAttributes(a: ResolvedAttribute): List<ResolvedAttribute> =
        a.classifierKeys.ifEmpty { listOf(a.name) }.map { key ->
            val standard = catalog.lookup(a.baseScope, key)
            if (standard != null) {
                a.copy(
                    name = key,
                    kind = AttributeKind.STANDARD,
                    xesStandardName = standard.canonicalName,
                    classifierName = null,
                    classifierKeys = emptyList(),
                    type = standard.type,
                )
            } else {
                a.copy(
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

    fun propertyRef(a: ResolvedAttribute, state: CypherBuildState?): PropertyRef {
        val physicalScope = a.baseScope
        val hoisted = physicalScope == Scope.EVENT && a.effectiveScope != Scope.EVENT
        val hoistedNodeVar = state?.hoistedEventNodeVar
        val nodeVar = if (hoisted && hoistedNodeVar != null) {
            hoistedNodeVar
        } else {
            nodeVarFor(physicalScope)
        }
        return if (physicalScope == a.effectiveScope) {
            propertyMapper.map(a, nodeVar)
        } else {
            propertyMapper.map(a.copy(effectiveScope = physicalScope), nodeVar)
        }
    }

    fun nodeVarFor(scope: Scope): String = when (scope) {
        Scope.LOG -> "log"
        Scope.TRACE -> "trace"
        Scope.EVENT -> "event"
    }

    fun exprKey(e: ResolvedExpression): String = PqlExpressionText.of(e)

    private fun renderBinaryOp(e: TypedBinaryOp, s: CypherBuildState): String =
        renderNestedAttributeComparison(e, s)
            ?: renderTemporalAggregationDifference(e, s)
            ?: renderRegularBinaryOp(e, s)

    private fun renderNestedAttributeComparison(e: TypedBinaryOp, s: CypherBuildState): String? {
        if (!isEqualityOperator(e.op)) return null
        if (!isNestedAttribute(e.left) && !isNestedAttribute(e.right)) return null

        val operator = if (e.op == BinaryOperator.EQ) "=" else "<>"
        return "toString(${render(e.left, s)}) $operator toString(${render(e.right, s)})"
    }

    private fun renderTemporalAggregationDifference(e: TypedBinaryOp, s: CypherBuildState): String? {
        if (e.op != BinaryOperator.MINUS) return null
        if (!CypherAggregationInspector.isTemporalAggregation(e.left)) return null
        if (!CypherAggregationInspector.isTemporalAggregation(e.right)) return null

        return renderTemporalDifferenceInDays(e.left, e.right, s)
    }

    private fun renderRegularBinaryOp(e: TypedBinaryOp, s: CypherBuildState): String {
        val left = render(e.left, s)
        val right = renderBinaryRightOperand(e, s)

        return when (e.op) {
            BinaryOperator.EQ -> "$left = $right"
            BinaryOperator.NEQ -> "$left <> $right"
            BinaryOperator.LT -> "$left < $right"
            BinaryOperator.LTE -> "$left <= $right"
            BinaryOperator.GT -> "$left > $right"
            BinaryOperator.GTE -> "$left >= $right"
            BinaryOperator.AND -> "($left) AND ($right)"
            BinaryOperator.OR -> "($left) OR ($right)"
            BinaryOperator.PLUS -> "$left + $right"
            BinaryOperator.MINUS -> "$left - $right"
            BinaryOperator.MUL -> "$left * $right"
            BinaryOperator.DIV -> "$left / $right"
            BinaryOperator.IS -> "$left IS $right"
            BinaryOperator.IS_NOT -> "$left IS NOT $right"
            BinaryOperator.IN -> "$left IN $right"
            BinaryOperator.NOT_IN -> "NOT ($left IN $right)"
            BinaryOperator.LIKE -> "$left =~ $right"
            BinaryOperator.NOT_LIKE -> "NOT ($left =~ $right)"
            BinaryOperator.MATCHES_REGEX -> "$left =~ $right"
        }
    }

    private fun renderBinaryRightOperand(e: TypedBinaryOp, s: CypherBuildState): String {
        val likePattern = likePatternLiteral(e)
        return if (likePattern != null) {
            "\$${s.bindParam(CypherLikePattern.toRegex(likePattern))}"
        } else {
            render(e.right, s)
        }
    }

    private fun likePatternLiteral(e: TypedBinaryOp): String? {
        if (e.op != BinaryOperator.LIKE && e.op != BinaryOperator.NOT_LIKE) return null
        val literal = e.right as? TypedLiteral ?: return null
        return literal.value as? String
    }

    private fun isEqualityOperator(operator: BinaryOperator): Boolean =
        operator == BinaryOperator.EQ || operator == BinaryOperator.NEQ

    private fun renderScalarFunction(e: ScalarFunction, s: CypherBuildState): String {
        val name = e.name.lowercase()
        val args = e.arguments.map { render(it, s) }
        return when (name) {
            "now" -> "\$${s.queryNowParam()}"
            "dayofweek" -> {
                val arg = args.single()
                "toFloat(CASE $arg.dayOfWeek WHEN 7 THEN 1 ELSE $arg.dayOfWeek + 1 END)"
            }
            "year", "month", "day", "hour", "minute", "second" -> "toFloat(${args.single()}.$name)"
            "millisecond" -> "toFloat(${args.single()}.millisecond)"
            else -> "${e.name}(${args.joinToString(", ")})"
        }
    }

    private fun renderUnaryOp(e: TypedUnaryOp, s: CypherBuildState): String {
        val operand = render(e.operand, s)
        return when (e.op) {
            UnaryOperator.NOT -> "NOT ($operand)"
            UnaryOperator.NEGATE -> "-($operand)"
        }
    }

    private fun isNestedAttribute(e: ResolvedExpression): Boolean =
        e is ResolvedAttribute && NestedAttributePathCodec.parseEncoded(e.name) != null
}
