package com.processm.processminterpreter.pql

import com.processm.processminterpreter.pql.common.HierarchicalLimits

data class ExportQueryAsXesRequest(
    val query: String,
    val logId: String? = null,
    val dataStoreId: String? = null,
    val defaultLimits: HierarchicalLimits = HierarchicalLimits(),
    val compress: Boolean = false,
    val logName: String = "Query Result Log",
)
