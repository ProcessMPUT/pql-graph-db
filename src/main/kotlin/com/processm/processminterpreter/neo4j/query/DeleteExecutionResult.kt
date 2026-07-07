package com.processm.processminterpreter.neo4j.query

data class DeleteExecutionResult(
    val nodesDeleted: Int,
    val executedQueryDescription: String,
)
