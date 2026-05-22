package com.processm.processminterpreter.infrastructure.persistence.neo4j.query

import com.processm.processminterpreter.domain.pql.plan.LogicalPlan
import com.processm.processminterpreter.domain.pql.plan.CandidateLogPlan
import com.processm.processminterpreter.application.ports.DeleteExecutionResult
import com.processm.processminterpreter.application.ports.ExecutionOptions
import com.processm.processminterpreter.application.ports.QueryExecutionResult
import com.processm.processminterpreter.application.ports.QueryPlanExecutor
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher.CypherCodegen
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.cypher.CypherQuery
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.result.HierarchicalWindowing
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.result.HierarchyReconstructor
import com.processm.processminterpreter.infrastructure.persistence.neo4j.query.result.NodeRowHierarchyBuilder
import org.neo4j.driver.Driver
import org.neo4j.driver.Record
import org.slf4j.LoggerFactory

/**
 * Neo4j adapter for the [QueryPlanExecutor] port.
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
class Neo4jQueryPlanExecutor(
    private val driver: Driver,
    private val codegen: CypherCodegen,
    private val typeMapper: CypherTypeMapper,
    private val reconstructor: HierarchyReconstructor,
) : QueryPlanExecutor {
    private val logger = LoggerFactory.getLogger(Neo4jQueryPlanExecutor::class.java)

    override fun findMatchingLogIds(plan: CandidateLogPlan): List<String> {
        val cypher = codegen.generate(plan)
        logger.trace("Generated candidate-log Cypher:\n{}\nparams={}", cypher.cypher, cypher.parameters)
        return driver.session().use { session ->
            session.executeRead { tx ->
                tx.run(cypher.cypher, cypher.parameters)
                    .list { it["logId"].asString() }
            }
        }
    }

    override fun execute(plan: LogicalPlan.Select, options: ExecutionOptions): QueryExecutionResult {
        val effectivePlan = plan.copy(materializedScopes = options.materializedScopes)
        val cypher = codegen.generate(effectivePlan)
        logger.trace("Generated SELECT Cypher:\n{}\nparams={}", cypher.cypher, cypher.parameters)
        if (cypher.columnAliases.isEmpty()) {
            return executeNodeShaped(effectivePlan, cypher)
        }

        return executeProjected(effectivePlan, cypher)
    }

    private fun executeProjected(plan: LogicalPlan.Select, cypher: CypherQuery): QueryExecutionResult {
        val rows = readProjectedRows(cypher, plan.projectedRecordKeys(cypher))
        val logs = reconstructor.reconstruct(
            rows = rows,
            columnAliases = cypher.columnAliases,
            limits = plan.limits,
            offsets = plan.offsets,
            defaultLimits = plan.defaultLimits,
            selectAllScopes = plan.projection.selectAll.filterValues { it }.keys,
        )
        return QueryExecutionResult(
            logs = logs,
            rows = rows,
            rowCount = rows.size,
            executedQueryDescription = cypher.cypher,
        )
    }

    private fun executeNodeShaped(plan: LogicalPlan.Select, cypher: CypherQuery): QueryExecutionResult {
        val accumulator = NodeRowHierarchyBuilder().accumulator(
            selectAllScopes = plan.projection.selectAll.filterValues { it }.keys,
        )
        val rowCount = readNodeRows(cypher) { row ->
            accumulator.absorb(row)
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

    private fun readProjectedRows(
        cypher: CypherQuery,
        allowedKeys: Set<String>,
    ): List<Map<String, Any?>> {
        val rows = mutableListOf<Map<String, Any?>>()
        driver.session().use { session ->
            session.executeRead { tx ->
                val result = tx.run(cypher.cypher, cypher.parameters)
                while (result.hasNext()) {
                    rows += result.next().toProjectedRow(allowedKeys)
                }
            }
        }
        return rows
    }

    private fun readNodeRows(
        cypher: CypherQuery,
        absorb: (Map<String, Any?>) -> Unit,
    ): Int {
        var rowCount = 0
        driver.session().use { session ->
            session.executeRead { tx ->
                val result = tx.run(cypher.cypher, cypher.parameters)
                while (result.hasNext()) {
                    absorb(result.next().toNodeRow())
                    rowCount++
                }
            }
        }
        return rowCount
    }

    override fun executeDelete(plan: LogicalPlan.Delete): DeleteExecutionResult {
        val cypher = codegen.generate(plan)
        logger.trace("Generated DELETE Cypher:\n{}\nparams={}", cypher.cypher, cypher.parameters)
        val deletedCount = driver.session().use { session ->
            session.executeWrite { tx ->
                val result = tx.run(cypher.cypher, cypher.parameters)
                result.consume().counters().nodesDeleted()
            }
        }
        return DeleteExecutionResult(
            nodesDeleted = deletedCount,
            executedQueryDescription = cypher.cypher,
        )
    }

    private fun LogicalPlan.Select.projectedRecordKeys(cypher: CypherQuery): Set<String> {
        val selectAllNodeColumns = projection.selectAll.filterValues { it }.keys.map { it.nodeColumnName() }
        return cypher.columnAliases.keys + selectAllNodeColumns
    }

    private fun Record.toProjectedRow(allowedKeys: Set<String>): Map<String, Any?> =
        keys()
            .filter { it in allowedKeys }
            .associateWith { key -> typeMapper.toKotlin(this[key]) }

    private fun Record.toNodeRow(): Map<String, Any?> =
        keys().associateWith { key -> typeMapper.toKotlin(this[key]) }

    private fun com.processm.processminterpreter.domain.pql.catalog.Scope.nodeColumnName(): String = when (this) {
        com.processm.processminterpreter.domain.pql.catalog.Scope.LOG -> "log"
        com.processm.processminterpreter.domain.pql.catalog.Scope.TRACE -> "trace"
        com.processm.processminterpreter.domain.pql.catalog.Scope.EVENT -> "event"
    }
}
