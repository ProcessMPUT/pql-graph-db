package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.neo4j.property.NestedAttributePathCodec
import com.processm.processminterpreter.pql.catalog.AttributeKind
import com.processm.processminterpreter.pql.catalog.BinaryOperator
import com.processm.processminterpreter.pql.catalog.UnaryOperator
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.pql.catalog.Type
import com.processm.processminterpreter.pql.ast.PqlExpression

internal class CypherExpressionRenderer(
    private val propertyMapper: PhysicalAttributeMapper,
    private val catalog: StandardAttributeCatalog = StandardAttributeCatalog,
) {
    fun render(e: PqlExpression, s: CypherBuildState): String {
        if (s.canUseExpressionAliases) {
            s.expressionAlias(exprKey(e))?.let { return it }
        }
        return when (e) {
            is PqlExpression.Attribute -> renderAttribute(e, s)
            is PqlExpression.Literal -> e.value?.let { "\$${s.bindParam(it)}" } ?: "null"
            is PqlExpression.Binary -> renderBinaryOp(e, s)
            is PqlExpression.Unary -> renderUnaryOp(e, s)
            is PqlExpression.InList -> "[${e.values.joinToString(", ") { render(it, s) }}]"
            is PqlExpression.Call -> renderCall(e, s)
            is PqlExpression.Aggregation -> renderAggregation(e, s)
            is PqlExpression.AttributeRef ->
                error("Internal error: unresolved attribute reference '${e.rawText}' reached the Cypher renderer")
        }
    }

    fun renderWithHoistedEventNode(
        e: PqlExpression,
        s: CypherBuildState,
        eventNodeVar: String,
    ): String =
        s.withHoistedEventNodeVar(eventNodeVar) {
            render(e, s)
        }

    private fun renderAttribute(a: PqlExpression.Attribute, s: CypherBuildState): String {
        if (a.kind != AttributeKind.CLASSIFIER || a.classifierKeys.isEmpty()) {
            return propertyRef(a, s).toCypher()
        }
        return classifierKeyAttributes(a)
            .joinToString(" + ") { "coalesce(toString(${propertyRef(it, s).toCypher()}), '')" }
    }

    fun renderAggregation(e: PqlExpression.Aggregation, s: CypherBuildState): String {
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
        later: PqlExpression,
        earlier: PqlExpression,
        s: CypherBuildState,
    ): String {
        val laterRendered = render(later, s)
        val earlierRendered = render(earlier, s)
        return "toFloat(duration.inSeconds($earlierRendered, $laterRendered).seconds) / 86400.0"
    }

    private fun renderCountAggregation(argument: PqlExpression, renderedArgument: String): String {
        val attribute = argument as? PqlExpression.Attribute ?: return "count($renderedArgument)"
        if (attribute.baseScope == Scope.EVENT) return "count($renderedArgument)"

        val component = nodeVarFor(attribute.baseScope)
        return "count(DISTINCT CASE WHEN $renderedArgument IS NULL THEN null ELSE $component END)"
    }

    fun propertyRef(a: PqlExpression.Attribute): PropertyRef =
        propertyRef(a, state = null)

    fun classifierKeyAttributes(a: PqlExpression.Attribute): List<PqlExpression.Attribute> =
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

    fun propertyRef(a: PqlExpression.Attribute, state: CypherBuildState?): PropertyRef {
        val physicalScope = a.baseScope
        val hoisted = physicalScope == Scope.EVENT && a.effectiveScope != Scope.EVENT
        val hoistedNodeVar = state?.hoistedEventNodeVar
        val nodeVar = state?.attributeNodeVariable(a.baseScope, a.effectiveScope) ?: if (hoisted && hoistedNodeVar != null) {
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

    fun exprKey(e: PqlExpression): String = PqlExpressionText.of(e)

    private fun renderBinaryOp(e: PqlExpression.Binary, s: CypherBuildState): String =
        renderNestedAttributeComparison(e, s)
            ?: renderTemporalAggregationDifference(e, s)
            ?: renderRegularBinaryOp(e, s)

    private fun renderNestedAttributeComparison(e: PqlExpression.Binary, s: CypherBuildState): String? {
        if (!isEqualityOperator(e.op)) return null
        if (!isNestedAttribute(e.left) && !isNestedAttribute(e.right)) return null

        val operator = if (e.op == BinaryOperator.EQ) "=" else "<>"
        return "toString(${render(e.left, s)}) $operator toString(${render(e.right, s)})"
    }

    private fun renderTemporalAggregationDifference(e: PqlExpression.Binary, s: CypherBuildState): String? {
        if (e.op != BinaryOperator.MINUS) return null
        if (!CypherAggregationInspector.isTemporalAggregation(e.left)) return null
        if (!CypherAggregationInspector.isTemporalAggregation(e.right)) return null

        return renderTemporalDifferenceInDays(e.left, e.right, s)
    }

    private fun renderRegularBinaryOp(e: PqlExpression.Binary, s: CypherBuildState): String {
        val left = render(e.left, s)
        renderSimpleLike(e, left, s)?.let { return it }
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
            // ProcessM translates `matches` to PostgreSQL `~` (substring search,
            // unanchored); Neo4j `=~` is a whole-string match, so wrap the
            // pattern to search anywhere. `(?s)` lets the wrap cross newlines;
            // explicit ^/$ anchors inside the pattern keep working.
            BinaryOperator.MATCHES_REGEX -> "$left =~ ('(?s).*(?:' + $right + ').*')"
        }
    }

    private fun renderSimpleLike(
        e: PqlExpression.Binary,
        left: String,
        s: CypherBuildState,
    ): String? {
        val pattern = likePatternLiteral(e) ?: return null
        val parsed = parseSimpleLikePattern(pattern) ?: return null
        val parameter = "\$${s.bindParam(parsed.literal)}"
        val predicate = when (parsed.operator) {
            SimpleLikeOperator.EQUALS -> "$left = $parameter"
            SimpleLikeOperator.STARTS_WITH -> "$left STARTS WITH $parameter"
            SimpleLikeOperator.ENDS_WITH -> "$left ENDS WITH $parameter"
            SimpleLikeOperator.CONTAINS -> "$left CONTAINS $parameter"
        }
        return if (e.op == BinaryOperator.NOT_LIKE) "NOT ($predicate)" else predicate
    }

    /**
     * Lowers only patterns whose wildcard shape has a direct Cypher string
     * operator. Internal `%` segments and `_` retain the regex fallback.
     */
    private fun parseSimpleLikePattern(pattern: String): SimpleLikePattern? {
        val parts = mutableListOf(StringBuilder())
        var previousWasWildcard = false
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    val next = pattern[i + 1]
                    if (next != '%' && next != '_') parts.last().append('\\')
                    parts.last().append(next)
                    previousWasWildcard = false
                    i += 2
                }
                c == '%' -> {
                    if (!previousWasWildcard) parts += StringBuilder()
                    previousWasWildcard = true
                    i++
                }
                c == '_' -> return null
                else -> {
                    parts.last().append(c)
                    previousWasWildcard = false
                    i++
                }
            }
        }

        return when {
            parts.size == 1 -> SimpleLikePattern(SimpleLikeOperator.EQUALS, parts.single().toString())
            parts.size == 2 && parts.first().isEmpty() && parts.last().isEmpty() ->
                SimpleLikePattern(SimpleLikeOperator.CONTAINS, "")
            parts.size == 2 && parts.last().isEmpty() ->
                SimpleLikePattern(SimpleLikeOperator.STARTS_WITH, parts.first().toString())
            parts.size == 2 && parts.first().isEmpty() ->
                SimpleLikePattern(SimpleLikeOperator.ENDS_WITH, parts.last().toString())
            parts.size == 3 && parts.first().isEmpty() && parts.last().isEmpty() ->
                SimpleLikePattern(SimpleLikeOperator.CONTAINS, parts[1].toString())
            else -> null
        }
    }

    private fun renderBinaryRightOperand(e: PqlExpression.Binary, s: CypherBuildState): String {
        val likePattern = likePatternLiteral(e)
        return if (likePattern != null) {
            "\$${s.bindParam(likePatternToRegex(likePattern))}"
        } else {
            render(e.right, s)
        }
    }

    private fun likePatternToRegex(pattern: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && i + 1 < pattern.length -> {
                    val next = pattern[i + 1]
                    if (next == '%' || next == '_') {
                        out.append(java.util.regex.Pattern.quote(next.toString()))
                    } else {
                        out.append(java.util.regex.Pattern.quote("\\$next"))
                    }
                    i += 2
                }
                c == '%' -> {
                    out.append(".*")
                    i++
                }
                c == '_' -> {
                    out.append('.')
                    i++
                }
                else -> {
                    out.append(java.util.regex.Pattern.quote(c.toString()))
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun likePatternLiteral(e: PqlExpression.Binary): String? {
        if (e.op != BinaryOperator.LIKE && e.op != BinaryOperator.NOT_LIKE) return null
        val literal = e.right as? PqlExpression.Literal ?: return null
        return literal.value as? String
    }

    private data class SimpleLikePattern(
        val operator: SimpleLikeOperator,
        val literal: String,
    )

    private enum class SimpleLikeOperator {
        EQUALS,
        STARTS_WITH,
        ENDS_WITH,
        CONTAINS,
    }

    private fun isEqualityOperator(operator: BinaryOperator): Boolean =
        operator == BinaryOperator.EQ || operator == BinaryOperator.NEQ

    private fun renderCall(e: PqlExpression.Call, s: CypherBuildState): String {
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
            // Cypher spells these differently than PQL/SQL: there is no upper()/
            // lower() (only toUpper/toLower) and quarter is a datetime component,
            // not a function.
            "upper" -> "toUpper(${args.single()})"
            "lower" -> "toLower(${args.single()})"
            "quarter" -> "toFloat(${args.single()}.quarter)"
            else -> "${e.name}(${args.joinToString(", ")})"
        }
    }

    private fun renderUnaryOp(e: PqlExpression.Unary, s: CypherBuildState): String {
        val operand = render(e.operand, s)
        return when (e.op) {
            UnaryOperator.NOT -> "NOT ($operand)"
            UnaryOperator.NEGATE -> "-($operand)"
        }
    }

    private fun isNestedAttribute(e: PqlExpression): Boolean =
        e is PqlExpression.Attribute && NestedAttributePathCodec.isEncoded(e.name)
}
