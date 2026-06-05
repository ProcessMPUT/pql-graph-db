package com.processm.processminterpreter.application.ports

import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.plan.CandidateLogPlan
import com.processm.processminterpreter.domain.pql.common.HierarchicalLimits
import com.processm.processminterpreter.domain.pql.plan.LogicalPlan

interface QueryPlanExecutor {
    fun execute(plan: LogicalPlan.Select, options: ExecutionOptions = ExecutionOptions()): QueryExecutionResult
    fun findMatchingLogIds(plan: CandidateLogPlan): List<String>
    fun executeDelete(plan: LogicalPlan.Delete): DeleteExecutionResult
}

data class ExecutionOptions(
    val defaultLimits: HierarchicalLimits = HierarchicalLimits(),
    val materializedScopes: Set<Scope> = emptySet(),
)

data class QueryExecutionResult(
    val logs: List<XesLog>,
    val rows: List<Map<String, Any?>>,
    val rowCount: Int,
    val executedQueryDescription: String,
)

data class DeleteExecutionResult(
    val nodesDeleted: Int,
    val executedQueryDescription: String,
)
