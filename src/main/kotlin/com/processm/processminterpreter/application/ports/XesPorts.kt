package com.processm.processminterpreter.application.ports

import com.processm.processminterpreter.domain.log.xes.XesLog
import java.io.InputStream
import java.io.OutputStream

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

interface XesWriter {
    fun write(logs: List<XesLog>, output: OutputStream, options: XesWriteOptions = XesWriteOptions())
}

data class XesWriteOptions(
    val compress: Boolean = false,
    val logName: String = "Query Result Log",
)
