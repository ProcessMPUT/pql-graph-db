package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.plan.LogicalPlan
import com.processm.processminterpreter.domain.pql.plan.Projection

internal class CypherDeleteRenderer(
    private val filterRenderer: CypherFilterRenderer,
) {
    fun render(plan: LogicalPlan.Delete): CypherQuery {
        val shadow = LogicalPlan.Select(
            source = plan.source,
            projection = Projection(columns = emptyList()),
            filter = plan.filter,
            location = plan.location,
        )
        val s = CypherBuildState(shadow)
        CypherMatchEmitter.emit(s)
        filterRenderer.emitWhereClause(s)
        when (plan.target) {
            Scope.LOG -> s.cypher.append(
                " WITH DISTINCT log" +
                    " OPTIONAL MATCH (log)-[:CONTAINS]->(trace:Trace)" +
                    " OPTIONAL MATCH (trace)-[:HAS_EVENT]->(event:Event)" +
                    " WITH collect(DISTINCT log) AS logs, collect(DISTINCT trace) AS traces, collect(DISTINCT event) AS events" +
                    " FOREACH (e IN events | DETACH DELETE e)" +
                    " FOREACH (t IN traces | DETACH DELETE t)" +
                    " FOREACH (l IN logs | DETACH DELETE l)",
            )
            Scope.TRACE -> s.cypher.append(
                " WITH DISTINCT trace" +
                    " OPTIONAL MATCH (trace)-[:HAS_EVENT]->(event:Event)" +
                    " WITH collect(DISTINCT trace) AS traces, collect(DISTINCT event) AS events" +
                    " FOREACH (e IN events | DETACH DELETE e)" +
                    " FOREACH (t IN traces | DETACH DELETE t)",
            )
            Scope.EVENT -> s.cypher.append(" DETACH DELETE event")
        }
        return s.finish()
    }
}
