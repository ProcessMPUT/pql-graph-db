package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.ast.PqlExpression

internal object PqlExpressionText {
    fun of(e: PqlExpression): String = of(e, preserveBracketedAttributes = false)

    private fun of(e: PqlExpression, preserveBracketedAttributes: Boolean): String = when (e) {
        is PqlExpression.Attribute -> {
            val hoisting = "^".repeat(e.baseScope.ordinal - e.effectiveScope.ordinal)
            val text = "$hoisting${e.baseScope.fullName}:${e.xesStandardName ?: e.name}"
            if (preserveBracketedAttributes && e.wasBracketed) "[$text]" else text
        }
        is PqlExpression.Aggregation -> {
            val text = "${e.name}(${of(e.argument, preserveBracketedAttributes = true)})"
            e.scope?.let { "${it.fullName}:$text" } ?: text
        }
        is PqlExpression.Call -> {
            val text = "${e.name}(${e.arguments.joinToString(",") { of(it, preserveBracketedAttributes = true) }})"
            e.scope?.let { "${it.fullName}:$text" } ?: text
        }
        is PqlExpression.Binary -> "${of(e.left, preserveBracketedAttributes = true)} ${e.op.symbol} ${of(e.right, preserveBracketedAttributes = true)}"
        is PqlExpression.Unary -> "${e.op}(${of(e.operand, preserveBracketedAttributes = true)})"
        is PqlExpression.Literal -> e.scope?.let { "${it.fullName}:${e.pqlText}" } ?: e.pqlText
        is PqlExpression.InList -> "(${e.values.joinToString(",") { of(it, preserveBracketedAttributes = true) }})"
        is PqlExpression.AttributeRef -> e.rawText
    }
}
