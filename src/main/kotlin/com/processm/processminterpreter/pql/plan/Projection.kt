package com.processm.processminterpreter.pql.plan

import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.ast.PqlExpression

/**
 * A single output column in the logical plan, together with the hierarchy level it
 * belongs to in the XES projection (so reconstruction knows whether to attach the
 * value to log, trace, or event).
 */
data class ProjectedColumn(
    val expression: PqlExpression,
    val alias: String,
    val scope: Scope,
)

/**
 * Projection portion of the plan. [selectAll] is a per-scope flag: true when the
 * query wrote `SELECT l:*` / `t:*` / `e:*` at that scope. [implicitAll] marks the
 * ProcessM-style default hierarchy projection used when the query has no SELECT
 * clause at all; it must remain distinguishable from an explicit event wildcard.
 */
data class Projection(
    val columns: List<ProjectedColumn>,
    val selectAll: Map<Scope, Boolean> = emptyMap(),
    val implicitAll: Boolean = false,
)
