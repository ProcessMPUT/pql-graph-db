package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.resolved.Aggregation
import com.processm.processminterpreter.domain.pql.resolved.ResolvedAttribute
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression
import com.processm.processminterpreter.domain.pql.resolved.ResolvedInList
import com.processm.processminterpreter.domain.pql.resolved.ScalarFunction
import com.processm.processminterpreter.domain.pql.resolved.TypedBinaryOp
import com.processm.processminterpreter.domain.pql.resolved.TypedLiteral
import com.processm.processminterpreter.domain.pql.resolved.TypedUnaryOp

internal object PqlExpressionText {
    fun of(e: ResolvedExpression): String = of(e, preserveBracketedAttributes = false)

    private fun of(e: ResolvedExpression, preserveBracketedAttributes: Boolean): String = when (e) {
        is ResolvedAttribute -> {
            val hoisting = "^".repeat(e.baseScope.ordinal - e.effectiveScope.ordinal)
            val text = "$hoisting${e.baseScope.fullName}:${e.xesStandardName ?: e.name}"
            if (preserveBracketedAttributes && e.wasBracketed) "[$text]" else text
        }
        is Aggregation -> {
            val text = "${e.name}(${of(e.argument, preserveBracketedAttributes = true)})"
            e.scope?.let { "${it.fullName}:$text" } ?: text
        }
        is ScalarFunction -> {
            val text = "${e.name}(${e.arguments.joinToString(",") { of(it, preserveBracketedAttributes = true) }})"
            e.scope?.let { "${it.fullName}:$text" } ?: text
        }
        is TypedBinaryOp -> "${of(e.left, preserveBracketedAttributes = true)} ${e.op.symbol} ${of(e.right, preserveBracketedAttributes = true)}"
        is TypedUnaryOp -> "${e.op}(${of(e.operand, preserveBracketedAttributes = true)})"
        is TypedLiteral -> e.scope?.let { "${it.fullName}:${e.pqlText}" } ?: e.pqlText
        is ResolvedInList -> "(${e.values.joinToString(",") { of(it, preserveBracketedAttributes = true) }})"
    }
}
