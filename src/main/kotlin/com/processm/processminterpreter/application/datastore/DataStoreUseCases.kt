package com.processm.processminterpreter.application.datastore

import com.processm.processminterpreter.domain.datastore.DataStore
import com.processm.processminterpreter.application.ports.DataStoreNotFoundException
import com.processm.processminterpreter.application.ports.DataStoreRepository
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID

@Component
class CreateDataStoreUseCase(private val dataStores: DataStoreRepository) {
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
}

@Component
class ListDataStoresUseCase(private val dataStores: DataStoreRepository) {
    fun list(): List<DataStore> = dataStores.findAll()
}

@Component
class GetDataStoreUseCase(private val dataStores: DataStoreRepository) {
    fun get(id: String): DataStore = dataStores.findById(id) ?: throw DataStoreNotFoundException(id)
}

@Component
class RenameDataStoreUseCase(private val dataStores: DataStoreRepository) {
    fun rename(id: String, name: String): DataStore {
        val current = dataStores.findById(id) ?: throw DataStoreNotFoundException(id)
        return dataStores.update(current.copy(name = name))
    }
}

@Component
class DeleteDataStoreUseCase(private val dataStores: DataStoreRepository) {
    fun delete(id: String): Boolean = dataStores.deleteWithLogs(id)
}

data class CreateDataStoreRequest(
    val name: String,
    val id: String? = null,
)
