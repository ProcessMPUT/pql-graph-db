package com.processm.processminterpreter.xes

import com.processm.processminterpreter.xes.datastore.DataStore
import java.time.LocalDateTime

interface DataStoreRepository {
    fun save(dataStore: DataStore): DataStore
    fun findById(id: String): DataStore?
    fun findAll(): List<DataStore>
    fun findLogSummaries(dataStoreId: String): List<DataStoreLogSummary>
    fun update(dataStore: DataStore): DataStore
    fun deleteWithLogs(id: String): Boolean
    fun exists(id: String): Boolean
    fun attachLog(dataStoreId: String, logId: String)
}

data class DataStoreLogSummary(
    val logId: String,
    val name: String,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
)
