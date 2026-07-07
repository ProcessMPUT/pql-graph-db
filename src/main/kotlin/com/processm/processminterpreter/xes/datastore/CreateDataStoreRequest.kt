package com.processm.processminterpreter.xes.datastore

data class CreateDataStoreRequest(
    val name: String,
    val id: String? = null,
)
