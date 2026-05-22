package com.processm.processminterpreter.application.datastore

import com.processm.processminterpreter.application.ports.DataStoreLogSummary
import com.processm.processminterpreter.application.ports.DataStoreNotFoundException
import com.processm.processminterpreter.application.ports.DataStoreRepository
import org.springframework.stereotype.Component

@Component
class ListDataStoreLogsUseCase(
    private val dataStores: DataStoreRepository,
) {
    fun list(dataStoreId: String): List<DataStoreLogSummary> {
        if (!dataStores.exists(dataStoreId)) {
            throw DataStoreNotFoundException(dataStoreId)
        }
        return dataStores.findLogSummaries(dataStoreId)
    }
}
