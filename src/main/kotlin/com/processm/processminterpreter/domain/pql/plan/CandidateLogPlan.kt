package com.processm.processminterpreter.domain.pql.plan

import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.common.OrderKey
import com.processm.processminterpreter.domain.pql.resolved.ResolvedExpression

/**
 * Backend-agnostic probe used before per-log classifier specialization.
 *
 * It carries only the query parts needed to decide which logs can participate in
 * the final result. Classifiers are forbidden in WHERE, so this probe can be
 * compiled before classifier definitions are resolved per log.
 */
data class CandidateLogPlan(
    val source: LogicalSource,
    val filter: ResolvedExpression?,
    val orderBy: List<OrderKey> = emptyList(),
    val location: SourceLocation,
)
