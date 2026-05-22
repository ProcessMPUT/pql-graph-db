package com.processm.processminterpreter.domain.pql.syntax

import com.processm.processminterpreter.domain.pql.catalog.SourceLocation

/**
 * Attribute reference as it appears in source, with lexical structure parsed but
 * zero semantic interpretation. Produced by [AttributeReferenceParser].
 *
 * Example:
 *   source: "[^^e:cost:total]"
 *   -> RawAttributeRef(rawText="[^^e:cost:total]", hoisting=2, scopeHint="e",
 *                     name="cost:total", wasBracketed=true, location=...)
 *
 * - [hoisting] is the count of leading `^` characters (0 if none).
 * - [scopeHint] is the scope token as written (e.g. "e" or "event"), null if absent
 *   or unrecognizable as a scope.
 * - [name] is the attribute name with any colons preserved (e.g. "org:group").
 * - [wasBracketed] distinguishes `[x]` (custom attribute) from `x` (standard-or-custom).
 */
data class RawAttributeRef(
    val rawText: String,
    val hoisting: Int,
    val scopeHint: String?,
    val name: String,
    val wasBracketed: Boolean,
    override val location: SourceLocation,
) : RawExpression
