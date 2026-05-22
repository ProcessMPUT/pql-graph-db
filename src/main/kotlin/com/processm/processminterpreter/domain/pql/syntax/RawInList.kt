package com.processm.processminterpreter.domain.pql.syntax

import com.processm.processminterpreter.domain.pql.catalog.SourceLocation

/**
 * Right-hand side of `x IN (...)` / `x NOT IN (...)`.
 * Members can be literals or attribute references (per grammar `id_or_scalar_list`).
 */
data class RawInList(
    val values: List<RawExpression>,
    override val location: SourceLocation,
) : RawExpression
