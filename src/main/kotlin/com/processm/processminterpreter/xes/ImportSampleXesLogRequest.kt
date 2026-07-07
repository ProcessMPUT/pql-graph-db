package com.processm.processminterpreter.xes

data class ImportSampleXesLogRequest(
    val resourcePath: String,
    val logId: String? = null,
    val dataStoreId: String? = null,
)
