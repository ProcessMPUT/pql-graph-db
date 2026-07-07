package com.processm.processminterpreter.xes.io

data class XesWriteOptions(
    val compress: Boolean = false,
    val logName: String = "Query Result Log",
)
