package com.processm.processminterpreter.domain.pql.syntax

import com.processm.processminterpreter.domain.pql.catalog.SourceLocation

/**
 * Literal as parsed from source. The raw text is preserved; typed interpretation
 * (string -> String, number -> Long/Double, etc.) happens in the resolver.
 */
data class RawLiteral(
    val rawText: String,
    val kind: RawLiteralKind,
    override val location: SourceLocation,
) : RawExpression

enum class RawLiteralKind { STRING, NUMBER, DATETIME, BOOLEAN, NULL, UUID }
