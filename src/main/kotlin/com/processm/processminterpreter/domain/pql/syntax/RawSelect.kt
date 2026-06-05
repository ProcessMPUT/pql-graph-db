package com.processm.processminterpreter.domain.pql.syntax

import com.processm.processminterpreter.domain.pql.catalog.Scope

/**
 * Parsed SELECT column: either an expression (with optional alias), or a scope star
 * (SELECT l:* / t:* / e:*). Exactly one of [expression] and [starAt] is non-null.
 */
data class RawSelectColumn(
    val expression: RawExpression?,
    val alias: String? = null,
    val starAt: Scope? = null,
)
