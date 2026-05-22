package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope

internal object CypherMatchEmitter {
    fun emit(s: CypherBuildState) {
        emitLog(s)
        if (Scope.TRACE in s.facts.usedScopes || Scope.EVENT in s.facts.usedScopes) {
            s.cypher.append("-[:CONTAINS]->(trace:Trace)")
        }
        if (Scope.EVENT in s.facts.usedScopes) {
            s.cypher.append("-[:HAS_EVENT]->(event:Event)")
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
