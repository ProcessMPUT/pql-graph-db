package com.processm.processminterpreter.pql.plan

import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.ast.PqlQuery
import com.processm.processminterpreter.pql.ast.PqlExpression

/**
 * Backend-agnostic probe used before per-log classifier specialization.
 *
 * It carries only the query parts needed to decide which logs can participate in
 * the final result. Classifiers are forbidden in WHERE, so this probe can be
 * compiled before classifier definitions are resolved per log.
 */
data class CandidateLogPlan(
    val source: LogicalSource,
    val filter: PqlExpression?,
    val orderBy: List<PqlQuery.OrderKey> = emptyList(),
    val location: SourceLocation,
)
