package com.processm.processminterpreter.xes

data class LogImportResult(
    val success: Boolean,
    val logId: String? = null,
    val traceCount: Int = 0,
    val eventCount: Int = 0,
    val message: String = "",
    val error: String? = null,
)
