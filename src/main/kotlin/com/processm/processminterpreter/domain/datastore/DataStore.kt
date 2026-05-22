package com.processm.processminterpreter.domain.datastore

import java.time.LocalDateTime

/**
 * ProcessM-compatible container for event logs.
 *
 * A data store is the query boundary exposed by the compatibility API:
 * querying `/data-stores/{id}/logs` must only see logs attached to that store.
 */
data class DataStore(
    val id: String,
    val name: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
)
