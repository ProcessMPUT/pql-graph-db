package com.processm.processminterpreter.pql.ast

import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.error.PQLSyntaxException
import com.processm.processminterpreter.pql.error.Problem

/**
 * Lexical decomposition of an attribute reference string into structured parts.
 *
 * Throws [PQLSyntaxException] only for *lexical* errors (empty text, unbracketed
 * whitespace). Does NOT validate scope semantics, attribute existence, or
 * hoisting bounds — those checks belong to later pipeline phases.
 *
 * Accepted shapes:
 *   name                     -> AttributeRef(name="name", scopeHint=null, hoisting=0)
 *   e:name / event:name      -> scopeHint recorded
 *   ^e:name / ^^e:timestamp  -> hoisting = count of `^`
 *   [e:customAttr]           -> wasBracketed=true
 *   [trace:name with spaces] -> spaces allowed inside brackets
 *   e:org:group              -> name keeps embedded colons (cost:total, org:group)
 *   org:group                -> when prefix is not a scope, the whole string is the name
 */
object AttributeReferenceParser {
    // (hoisting) (optional scope:) (name, possibly containing colons)
    private val REGEX = Regex("^(\\^*)(?:([a-zA-Z]+):)?(.+)$")

    fun parse(rawText: String, loc: SourceLocation): PqlExpression.AttributeRef {
        val wasBracketed = rawText.length >= 2 && rawText.startsWith("[") && rawText.endsWith("]")
        val inner = if (wasBracketed) rawText.substring(1, rawText.length - 1) else rawText

        if (inner.isBlank()) {
            throw PQLSyntaxException(loc, "Attribute name cannot be empty")
        }

        val match = REGEX.find(inner)
            ?: throw PQLSyntaxException(loc, "Invalid attribute syntax: $rawText")

        val hoistingStr = match.groupValues[1]
        val scopeRaw = match.groupValues[2].takeIf { it.isNotEmpty() }
        val nameRaw = match.groupValues[3]

        // If scopeRaw is not recognizable as a scope, treat entire "scopeRaw:nameRaw" as name.
        val (scopeHint, name) = when {
            scopeRaw == null -> null to nameRaw
            isKnownScopeHint(scopeRaw) -> scopeRaw to nameRaw
            else -> null to "$scopeRaw:$nameRaw"
        }

        if (!wasBracketed && name.contains(' ')) {
            throw PQLSyntaxException(
                Problem.SyntaxError,
                loc,
                "Attribute name cannot contain spaces unless bracketed: $rawText",
            )
        }

        return PqlExpression.AttributeRef(
            rawText = rawText,
            hoisting = hoistingStr.length,
            scopeHint = scopeHint,
            name = name,
            wasBracketed = wasBracketed,
            location = loc,
        )
    }

    private fun isKnownScopeHint(s: String): Boolean = Scope.tryParse(s) != null
}
