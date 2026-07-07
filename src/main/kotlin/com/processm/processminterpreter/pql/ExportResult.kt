package com.processm.processminterpreter.pql

data class ExportResult(
    val logCount: Int,
    val rowCount: Int,
    val executedQueryDescription: String,
)
