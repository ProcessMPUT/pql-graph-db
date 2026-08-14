package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.plan.LogicalPlan
import com.processm.processminterpreter.pql.plan.Projection

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
        CypherMatchEmitter.emitPendingOptionalEventMatch(s)
        val (targetNode, targetId) = when (plan.target) {
            Scope.LOG -> "log" to "log.logId"
            Scope.TRACE -> "trace" to "trace.traceId"
            Scope.EVENT -> "event" to "event.eventId"
        }
        // Event materialization may use OPTIONAL MATCH when no clause references
        // event data. Empty traces then bind event=null, which is not a delete
        // target and must not become a permanently repeated null batch.
        s.cypher.append(
            " WITH DISTINCT $targetNode WHERE $targetNode IS NOT NULL" +
                " RETURN $targetId AS $DELETE_ID_ALIAS LIMIT ${'$'}$DELETE_BATCH_SIZE_PARAM",
        )
        s.bindNamedParam(DELETE_BATCH_SIZE_PARAM, DELETE_TARGET_BATCH_SIZE)
        return s.finish()
    }

    companion object {
        const val DELETE_ID_ALIAS = "_deleteId"
        const val DELETE_BATCH_SIZE_PARAM = "_deleteBatchSize"
        const val DELETE_TARGET_BATCH_SIZE = 1000
    }
}
