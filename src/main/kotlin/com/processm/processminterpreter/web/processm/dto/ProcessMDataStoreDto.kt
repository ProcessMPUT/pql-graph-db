package com.processm.processminterpreter.web.processm.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.processm.processminterpreter.xes.DataStoreLogSummary
import com.processm.processminterpreter.xes.datastore.DataStore
import java.time.LocalDateTime

data class ProcessMDataStoreRequest(
    val name: String,
)

data class ProcessMDataStoreResponse(
    val name: String,
    val id: String,
    val propertySize: Long? = null,
    @param:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val createdAt: LocalDateTime? = null,
) {
    companion object {
        fun from(dataStore: DataStore, propertySize: Long? = null): ProcessMDataStoreResponse =
            ProcessMDataStoreResponse(
                name = dataStore.name,
                id = dataStore.id,
                propertySize = propertySize,
                createdAt = dataStore.createdAt,
            )
    }
}

data class ProcessMDataStoreLogSummaryResponse(
    val logId: String,
    val name: String,
    @param:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val createdAt: LocalDateTime? = null,
    @param:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    val updatedAt: LocalDateTime? = null,
) {
    companion object {
        fun from(summary: DataStoreLogSummary): ProcessMDataStoreLogSummaryResponse =
            ProcessMDataStoreLogSummaryResponse(
                logId = summary.logId,
                name = summary.name,
                createdAt = summary.createdAt,
                updatedAt = summary.updatedAt,
            )
    }
}
