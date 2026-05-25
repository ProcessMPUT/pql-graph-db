package com.processm.processminterpreter.application.datastore

class DataStoreNotFoundException(
    id: String,
) : RuntimeException("Data store with ID '$id' not found")
