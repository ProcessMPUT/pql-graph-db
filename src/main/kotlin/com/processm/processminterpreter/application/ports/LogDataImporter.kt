package com.processm.processminterpreter.application.ports

import java.io.InputStream

interface LogDataImporter {
    fun import(input: InputStream, logId: String? = null): LogImportResult
}

data class LogImportResult(
    val success: Boolean,
    val logId: String? = null,
    val traceCount: Int = 0,
    val eventCount: Int = 0,
    val message: String = "",
    val error: String? = null,
)
