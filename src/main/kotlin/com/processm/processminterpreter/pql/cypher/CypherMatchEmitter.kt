package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.catalog.Scope

internal object CypherMatchEmitter {
    /**
     * A mandatory `-[:HAS_EVENT]->` expansion drops traces that have no events, which
     * is wrong whenever the event scope is only there to materialize output: ProcessM
     * counts an empty trace, so `select count(t:name)` must count it too. When no
     * clause of the query actually references events, expand them with OPTIONAL MATCH
     * instead — this also stops trace-level aggregates from walking every event of the
     * log just to be de-duplicated away.
     *
     * The optional expansion is DEFERRED, not emitted inline: in Cypher a `WHERE`
     * directly after `OPTIONAL MATCH` becomes part of the optional pattern (failing
     * rows come back with nulls instead of being filtered out). Callers must emit the
     * WHERE clause first and then call [emitPendingOptionalEventMatch].
     */
    fun emit(s: CypherBuildState) {
        emitLog(s)
        if (Scope.TRACE in s.facts.usedScopes || Scope.EVENT in s.facts.usedScopes) {
            s.cypher.append("-[:CONTAINS]->(trace:Trace)")
        }
        if (Scope.EVENT in s.facts.usedScopes) {
            if (s.facts.eventScopeOnlyMaterialized) {
                s.markPendingOptionalEventMatch()
            } else {
                s.cypher.append("-[:HAS_EVENT]->(event:Event)")
            }
        }
    }

    /** Emits the deferred `OPTIONAL MATCH` for events; no-op when nothing is pending. */
    fun emitPendingOptionalEventMatch(s: CypherBuildState) {
        if (s.consumePendingOptionalEventMatch()) {
            s.cypher.append(" OPTIONAL MATCH (trace)-[:HAS_EVENT]->(event:Event)")
        }
    }

    fun emitLog(s: CypherBuildState) {
        val logId = s.plan.source.logId
        val dataStoreId = s.plan.source.dataStoreId

        if (logId != null) {
            s.cypher.append("MATCH (log:Log {logId: \$logId})")
            s.bindNamedParam("logId", logId)
        } else if (dataStoreId != null) {
            s.cypher.append("MATCH (:DataStore {dataStoreId: \$dataStoreId})-[:CONTAINS_LOG]->(log:Log)")
            s.bindNamedParam("dataStoreId", dataStoreId)
        } else {
            s.cypher.append("MATCH (log:Log)")
        }
    }
}
