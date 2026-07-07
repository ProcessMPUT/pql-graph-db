package com.processm.processminterpreter.pql

data class PqlQueryStatisticsSummary(
    val totalQueries: Int,
    val successfulQueries: Int,
    val failedQueries: Int,
    val averageExecutionTime: Double,
)
