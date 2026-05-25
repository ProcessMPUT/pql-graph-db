package com.processm.processminterpreter.application.ports

import com.processm.processminterpreter.domain.log.xes.XesLog
import java.io.OutputStream

interface XesWriter {
    fun write(logs: List<XesLog>, output: OutputStream, options: XesWriteOptions = XesWriteOptions())
}

data class XesWriteOptions(
    val compress: Boolean = false,
    val logName: String = "Query Result Log",
)
