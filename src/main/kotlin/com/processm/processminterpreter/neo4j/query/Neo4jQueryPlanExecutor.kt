package com.processm.processminterpreter.neo4j.query

import com.processm.processminterpreter.pql.plan.LogicalPlan
import com.processm.processminterpreter.pql.plan.CandidateLogPlan
import com.processm.processminterpreter.neo4j.query.DeleteExecutionResult
import com.processm.processminterpreter.neo4j.query.ExecutionOptions
import com.processm.processminterpreter.neo4j.query.QueryExecutionResult
import com.processm.processminterpreter.pql.cypher.CypherCodegen
import com.processm.processminterpreter.pql.cypher.CypherDeleteRenderer
import com.processm.processminterpreter.pql.cypher.CypherQuery
import com.processm.processminterpreter.pql.cypher.SYNTHETIC_LOG_KEY_ALIAS
import com.processm.processminterpreter.pql.cypher.SYNTHETIC_LOG_METADATA_ALIAS
import com.processm.processminterpreter.pql.cypher.SYNTHETIC_LOG_NODE_ALIAS
import com.processm.processminterpreter.pql.cypher.SYNTHETIC_TRACE_PRESENT_ALIAS
import com.processm.processminterpreter.pql.cypher.SYNTHETIC_EVENT_PRESENT_ALIAS
import com.processm.processminterpreter.neo4j.query.result.HierarchicalWindowing
import com.processm.processminterpreter.neo4j.query.result.HierarchyReconstructor
import com.processm.processminterpreter.neo4j.query.result.NodeRowHierarchyBuilder
import com.processm.processminterpreter.neo4j.query.result.ProjectedRowHierarchyBuilder
import com.processm.processminterpreter.neo4j.query.result.nodeProperties
import com.processm.processminterpreter.neo4j.repository.deleteEventsBatched
import com.processm.processminterpreter.neo4j.repository.deleteLogSubtreesBatched
import com.processm.processminterpreter.neo4j.repository.deleteTraceSubtreesBatched
import com.processm.processminterpreter.pql.XesAttributeReadMode
import com.processm.processminterpreter.pql.catalog.Scope
import org.neo4j.driver.Driver
import org.neo4j.driver.Record
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Neo4j query plan executor.
 *
 * Runs a [LogicalPlan] end-to-end:
 *  1. [CypherCodegen] turns the plan into a parameterized Cypher query,
 *  2. the Neo4j driver executes it in a read (or write) transaction,
 *  3. each result row is materialized into Kotlin values via [CypherTypeMapper],
 *  4. [HierarchyReconstructor] groups rows back into a nested [XesLog] list.
 *
 * This class deliberately holds no query logic of its own — every decision about
 * *what* to query lives in the codegen, and every decision about *how* to present
 * results lives in the reconstructor. The executor is pure plumbing.
 */
@Component
class Neo4jQueryPlanExecutor(
    private val driver: Driver,
    private val codegen: CypherCodegen,
    private val typeMapper: CypherTypeMapper,
    private val reconstructor: HierarchyReconstructor,
) {
    private val logger = LoggerFactory.getLogger(Neo4jQueryPlanExecutor::class.java)

    fun findMatchingLogIds(plan: CandidateLogPlan): List<String> {
        val cypher = codegen.generate(plan)
        logger.trace("Generated candidate-log Cypher:\n{}\nparams={}", cypher.cypher, cypher.parameters)
        return driver.session().use { session ->
            session.executeRead { tx ->
                tx.run(cypher.cypher, cypher.parameters)
                    .list { it["logId"].asString() }
            }
        }
    }

    fun execute(plan: LogicalPlan.Select, options: ExecutionOptions = ExecutionOptions()): QueryExecutionResult {
        val effectivePlan = plan.copy(materializedScopes = options.materializedScopes)
        val cypher = codegen.generate(effectivePlan, options.attributeReadMode)
        logger.trace("Generated SELECT Cypher:\n{}\nparams={}", cypher.cypher, cypher.parameters)
        if (cypher.columnAliases.isEmpty()) {
            return executeNodeShaped(effectivePlan, cypher)
        }

        return executeProjected(effectivePlan, cypher)
    }

    private fun executeProjected(plan: LogicalPlan.Select, cypher: CypherQuery): QueryExecutionResult {
        if (plan.materializedScopes.isNotEmpty()) {
            return executeProjectedStreaming(plan, cypher)
        }

        val rows = readProjectedRows(cypher, plan.projectedRecordKeys(cypher))
        return QueryExecutionResult(
            logsProvider = {
                reconstructor.reconstruct(
                    rows = rows,
                    columnAliases = cypher.columnAliases,
                    limits = plan.limits,
                    offsets = plan.offsets,
                    defaultLimits = plan.defaultLimits,
                    selectAllScopes = plan.projection.selectAll.filterValues { it }.keys,
                )
            },
            rows = rows,
            rowCount = rows.size,
            executedQueryDescription = cypher.cypher,
        )
    }

    private fun executeProjectedStreaming(plan: LogicalPlan.Select, cypher: CypherQuery): QueryExecutionResult {
        val accumulator = ProjectedRowHierarchyBuilder().accumulator(
            columnAliases = cypher.columnAliases,
            selectAllScopes = plan.projection.selectAll.filterValues { it }.keys,
        )
        val allowedKeys = plan.projectedRecordKeys(cypher)
        var rowCount = 0
        driver.session().use { session ->
            session.executeRead { tx ->
                val seenLogKeys = linkedSetOf<String>()
                val result = tx.run(cypher.cypher, cypher.parameters)
                while (result.hasNext()) {
                    val row = result.next().toProjectedRow(allowedKeys)
                    if (cypher.hydrateLogProperties) {
                        (row[SYNTHETIC_LOG_KEY_ALIAS] as? String)?.let(seenLogKeys::add)
                    }
                    accumulator.absorb(row)
                    rowCount++
                }
                if (seenLogKeys.isNotEmpty()) {
                    val hydration = logPropertiesQuery(seenLogKeys, cypher.attributeReadMode)
                    val hydrationResult = tx.run(hydration.cypher, hydration.parameters)
                    while (hydrationResult.hasNext()) {
                        val hydrationRow = hydrationResult.next().toNodeRow()
                        val logId = hydrationRow[SYNTHETIC_LOG_KEY_ALIAS] as? String
                        if (logId != null) {
                            accumulator.hydrateLog(logId, hydrationRow.nodeProperties("log"))
                        }
                    }
                }
            }
        }
        val logs = HierarchicalWindowing.apply(
            logs = accumulator.build(),
            limits = plan.limits,
            offsets = plan.offsets,
            defaultLimits = plan.defaultLimits,
        )
        return QueryExecutionResult(
            logs = logs,
            rows = emptyList(),
            rowCount = rowCount,
            executedQueryDescription = cypher.cypher,
        )
    }

    private fun executeNodeShaped(plan: LogicalPlan.Select, cypher: CypherQuery): QueryExecutionResult {
        val accumulator = NodeRowHierarchyBuilder().accumulator(
            selectAllScopes = plan.projection.selectAll.filterValues { it }.keys,
        )
        val rowCount = readNodeRowsHydratingLogs(cypher, accumulator::absorb)
        val logs = HierarchicalWindowing.apply(
            logs = accumulator.build(),
            limits = plan.limits,
            offsets = plan.offsets,
            defaultLimits = plan.defaultLimits,
        )
        return QueryExecutionResult(
            logs = logs,
            rows = emptyList(),
            rowCount = rowCount,
            executedQueryDescription = cypher.cypher,
        )
    }

    private fun readProjectedRows(
        cypher: CypherQuery,
        allowedKeys: Set<String>,
    ): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        readProjectedRows(cypher, allowedKeys) { row ->
            rows += row
        }
        return rows
    }

    private fun readProjectedRows(
        cypher: CypherQuery,
        allowedKeys: Set<String>,
        absorb: (Map<String, Any?>) -> Unit,
    ): Int {
        var rowCount = 0
        driver.session().use { session ->
            session.executeRead { tx ->
                val result = tx.run(cypher.cypher, cypher.parameters)
                while (result.hasNext()) {
                    absorb(result.next().toProjectedRow(allowedKeys))
                    rowCount++
                }
            }
        }
        return rowCount
    }

    /**
     * Fetches full log properties once per distinct log for queries flagged with
     * [CypherQuery.hydrateLogProperties]. Shipping the log node on every hierarchy
     * row is the dominant cost of ordered/grouped reads on logs with large XES
     * metadata (Hospital_log: ~3.4k log attributes, ~8 MB per node copy).
     */
    private fun logPropertiesQuery(
        logIds: Collection<String>,
        attributeReadMode: XesAttributeReadMode,
    ): CypherQuery = CypherQuery(
        cypher = "MATCH (log:Log) WHERE log.logId IN \$logIds" +
            " RETURN log.logId AS $SYNTHETIC_LOG_KEY_ALIAS, " +
            if (attributeReadMode == XesAttributeReadMode.PROCESSM_JSON) {
                "[key IN keys(log) WHERE NOT key STARTS WITH '\u001f' " +
                    "AND NOT key STARTS WITH 'processm' | {key: key, value: log[key]}] AS log"
            } else {
                "properties(log) AS log"
            },
        parameters = mapOf("logIds" to logIds.toList()),
        columnAliases = emptyMap(),
        attributeReadMode = attributeReadMode,
    )

    /**
     * Streams node rows into [absorb]; for queries flagged with
     * [CypherQuery.hydrateLogProperties] it fetches the log properties INSIDE the
     * same read transaction, so the hierarchy rows and the log attributes come
     * from one consistent snapshot. Hydration rows are not counted in the result.
     */
    private fun readNodeRowsHydratingLogs(
        cypher: CypherQuery,
        absorb: (Map<String, Any?>) -> Unit,
    ): Int {
        var rowCount = 0
        driver.session().use { session ->
            session.executeRead { tx ->
                val seenLogKeys = linkedSetOf<String>()
                val result = tx.run(cypher.cypher, cypher.parameters)
                while (result.hasNext()) {
                    val row = result.next().toNodeRow()
                    if (cypher.hydrateLogProperties) {
                        (row[SYNTHETIC_LOG_KEY_ALIAS] as? String)?.let(seenLogKeys::add)
                    }
                    absorb(row)
                    rowCount++
                }
                if (seenLogKeys.isNotEmpty()) {
                    val hydration = logPropertiesQuery(seenLogKeys, cypher.attributeReadMode)
                    val hydrationResult = tx.run(hydration.cypher, hydration.parameters)
                    while (hydrationResult.hasNext()) {
                        absorb(hydrationResult.next().toNodeRow())
                    }
                }
            }
        }
        return rowCount
    }

    fun executeDelete(plan: LogicalPlan.Delete): DeleteExecutionResult {
        val selection = codegen.generate(plan)
        logger.trace("Generated DELETE target selection:\n{}\nparams={}", selection.cypher, selection.parameters)
        var deletedCount = 0L
        while (true) {
            val targetIds = driver.session().use { session ->
                session.executeRead { tx ->
                    tx.run(selection.cypher, selection.parameters)
                        .list { it[CypherDeleteRenderer.DELETE_ID_ALIAS].asString() }
                }
            }
            if (targetIds.isEmpty()) break

            logger.trace("Deleting {} {} targets in the current PQL batch", targetIds.size, plan.target)
            val deletedBatch = deleteTargetBatch(plan.target, targetIds)
            deletedCount += deletedBatch
        }
        return DeleteExecutionResult(
            nodesDeleted = deletedCount.toInt(),
            executedQueryDescription = selection.cypher + "\n-- targets are deleted in bounded batches --",
        )
    }

    private fun deleteTargetBatch(target: Scope, targetIds: List<String>): Long =
        driver.session().use { session ->
            val expectedDeleted = session.run(
                deleteSubtreeCountCypher(target),
                mapOf("targetIds" to targetIds),
            ).single()["nodes"].asLong()
            when (target) {
                Scope.EVENT -> deleteEventsBatched(session, targetIds)
                Scope.TRACE -> deleteTraceSubtreesBatched(session, targetIds)
                Scope.LOG -> {
                    val params = mapOf<String, Any?>("logIds" to targetIds)
                    deleteLogSubtreesBatched(
                        session,
                        "MATCH (log:Log) WHERE log.logId IN ${'$'}logIds WITH log MATCH (log)",
                        params,
                    )
                    session.run(
                        "UNWIND ${'$'}logIds AS logId MATCH (log:Log {logId: logId}) DETACH DELETE log",
                        params,
                    ).consume()
                }
            }
            val remainingTargets = session.run(
                remainingDeleteTargetsCypher(target),
                mapOf("targetIds" to targetIds),
            ).single()["nodes"].asLong()
            check(remainingTargets < targetIds.size) {
                "DELETE selected ${targetIds.size} ${target.name.lowercase()} targets but made no progress"
            }
            expectedDeleted
        }

    private fun deleteSubtreeCountCypher(target: Scope): String = when (target) {
        Scope.EVENT ->
            "UNWIND ${'$'}targetIds AS targetId MATCH (:Event {eventId: targetId}) RETURN count(*) AS nodes"
        Scope.TRACE ->
            "UNWIND ${'$'}targetIds AS targetId MATCH (trace:Trace {traceId: targetId})" +
                " RETURN sum(1 + COUNT { (trace)-[:HAS_EVENT]->(:Event) }) AS nodes"
        Scope.LOG ->
            "UNWIND ${'$'}targetIds AS targetId MATCH (log:Log {logId: targetId})" +
                " RETURN sum(1 + COUNT { (log)-[:CONTAINS]->(:Trace) } +" +
                " COUNT { (log)-[:CONTAINS]->(:Trace)-[:HAS_EVENT]->(:Event) }) AS nodes"
    }

    private fun remainingDeleteTargetsCypher(target: Scope): String = when (target) {
        Scope.EVENT ->
            "UNWIND ${'$'}targetIds AS targetId MATCH (:Event {eventId: targetId}) RETURN count(*) AS nodes"
        Scope.TRACE ->
            "UNWIND ${'$'}targetIds AS targetId MATCH (:Trace {traceId: targetId}) RETURN count(*) AS nodes"
        Scope.LOG ->
            "UNWIND ${'$'}targetIds AS targetId MATCH (:Log {logId: targetId}) RETURN count(*) AS nodes"
    }

    private fun LogicalPlan.Select.projectedRecordKeys(cypher: CypherQuery): Set<String> {
        val selectAllNodeColumns = projection.selectAll.filterValues { it }.keys.map { it.nodeColumnName() }
        return cypher.columnAliases.keys + selectAllNodeColumns +
            SYNTHETIC_LOG_METADATA_ALIAS + SYNTHETIC_LOG_NODE_ALIAS + SYNTHETIC_LOG_KEY_ALIAS +
            SYNTHETIC_TRACE_PRESENT_ALIAS + SYNTHETIC_EVENT_PRESENT_ALIAS
    }

    private fun Record.toProjectedRow(allowedKeys: Set<String>): Map<String, Any?> {
        val recordKeys = keys()
        val row = LinkedHashMap<String, Any?>(mapCapacity(minOf(recordKeys.size, allowedKeys.size)))
        for (key in recordKeys) {
            if (key in allowedKeys) row[key] = typeMapper.toKotlin(this[key])
        }
        return row
    }

    private fun Record.toNodeRow(): Map<String, Any?> {
        val recordKeys = keys()
        val row = LinkedHashMap<String, Any?>(mapCapacity(recordKeys.size))
        for (key in recordKeys) row[key] = typeMapper.toKotlin(this[key])
        return row
    }

    private fun mapCapacity(expectedSize: Int): Int =
        if (expectedSize < 3) expectedSize + 1 else expectedSize * 4 / 3 + 1

    private fun com.processm.processminterpreter.pql.catalog.Scope.nodeColumnName(): String = when (this) {
        com.processm.processminterpreter.pql.catalog.Scope.LOG -> "log"
        com.processm.processminterpreter.pql.catalog.Scope.TRACE -> "trace"
        com.processm.processminterpreter.pql.catalog.Scope.EVENT -> "event"
    }
}
