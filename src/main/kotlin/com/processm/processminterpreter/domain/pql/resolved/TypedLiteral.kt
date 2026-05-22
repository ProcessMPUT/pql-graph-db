package com.processm.processminterpreter.domain.pql.resolved

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.catalog.Type

/**
 * Literal after type inference.
 * [value] is the parsed Kotlin representation: String / Long / Double / Instant /
 * LocalDateTime / Boolean / UUID / null.
 */
data class TypedLiteral(
    val value: Any?,
    override val type: Type,
    override val location: SourceLocation,
    val scope: Scope? = null,
    val pqlText: String = value?.toString() ?: "null",
) : ResolvedExpression
