package com.processm.processminterpreter.xes

import java.io.InputStream

data class ImportXesLogRequest(
    val input: InputStream,
    val logId: String? = null,
    val dataStoreId: String? = null,
)
