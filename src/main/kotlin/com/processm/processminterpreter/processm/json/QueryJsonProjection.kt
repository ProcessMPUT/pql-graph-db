package com.processm.processminterpreter.processm.json

import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.pql.catalog.Scope

data class QueryJsonProjection(
    val logs: List<XesLog>,
    val hasExplicitSelect: Boolean = false,
    val selectAllScopes: Set<Scope> = emptySet(),
    val projectedLogAttributes: Set<String> = emptySet(),
    val projectedTraceStandardAttributes: Set<String> = emptySet(),
    val includeTraces: Boolean = true,
    val includeEvents: Boolean = true,
)

/**
 * Maps the ProcessM API's includeTraces/includeEvents flags to the set of scopes
 * that must be materialized: LOG always, TRACE when traces or events are requested,
 * EVENT only when events are requested.
 */
internal fun requestedScopes(includeTraces: Boolean, includeEvents: Boolean): Set<Scope> =
    buildSet {
        add(Scope.LOG)
        if (includeTraces || includeEvents) add(Scope.TRACE)
        if (includeEvents) add(Scope.EVENT)
    }
