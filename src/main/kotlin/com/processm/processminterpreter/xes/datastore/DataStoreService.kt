package com.processm.processminterpreter.xes.datastore

import com.processm.processminterpreter.xes.DataStoreLogSummary
import com.processm.processminterpreter.xes.DataStoreRepository
import com.processm.processminterpreter.xes.datastore.DataStore
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

@Service
class DataStoreService(private val dataStores: DataStoreRepository) {
    fun create(request: CreateDataStoreRequest): DataStore {
        val now = LocalDateTime.now()
        val id = request.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        return dataStores.save(
            DataStore(
                id = id,
                name = request.name,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    fun list(): List<DataStore> = dataStores.findAll()

    fun get(id: String): DataStore = dataStores.findById(id) ?: throw DataStoreNotFoundException(id)

    fun rename(id: String, name: String): DataStore {
        val current = get(id)
        return dataStores.update(current.copy(name = name))
    }

    fun delete(id: String): Boolean = dataStores.deleteWithLogs(id)

    fun listLogs(dataStoreId: String): List<DataStoreLogSummary> {
        if (!dataStores.exists(dataStoreId)) {
            throw DataStoreNotFoundException(dataStoreId)
        }
        return dataStores.findLogSummaries(dataStoreId)
    }
}
