package com.processm.processminterpreter.xes.datastore

class DataStoreNotFoundException(
    id: String,
) : RuntimeException("Data store with ID '$id' not found")
