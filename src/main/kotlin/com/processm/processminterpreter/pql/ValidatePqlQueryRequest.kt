package com.processm.processminterpreter.pql

data class ValidatePqlQueryRequest(
    val query: String,
    val logId: String? = null,
    val dataStoreId: String? = null,
)
