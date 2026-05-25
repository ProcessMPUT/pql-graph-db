package com.processm.processminterpreter.application.log

import com.processm.processminterpreter.application.ports.LogImportResult
import com.processm.processminterpreter.application.ports.LogDataImporter
import com.processm.processminterpreter.application.ports.LogRepository
import com.processm.processminterpreter.application.ports.DataStoreRepository
import com.processm.processminterpreter.application.ports.BundledLogResourceReader
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Application entry point for importing bundled sample XES resources.
 */
@Component
class ImportSampleXesLogUseCase(
    private val importer: LogDataImporter,
    private val logs: LogRepository,
    private val dataStores: DataStoreRepository,
    private val resources: BundledLogResourceReader,
) {
    private companion object {
        const val IMPORT_REJECTED = "Import rejected"
    }

    private val log = LoggerFactory.getLogger(ImportSampleXesLogUseCase::class.java)

    fun import(request: ImportSampleXesLogRequest): LogImportResult {
        val explicitId = request.logId?.takeIf { it.isNotBlank() }
        if (explicitId != null && logs.exists(explicitId)) {
            return LogImportResult(
                success = false,
                logId = explicitId,
                message = IMPORT_REJECTED,
                error = "Log with ID '$explicitId' already exists",
            )
        }
        val dataStoreId = request.dataStoreId?.takeIf { it.isNotBlank() }
        if (dataStoreId != null && !dataStores.exists(dataStoreId)) {
            return LogImportResult(
                success = false,
                logId = explicitId,
                message = IMPORT_REJECTED,
                error = "Data store with ID '$dataStoreId' does not exist",
            )
        }
        log.info(
            "Importing sample XES resource={} logId={} dataStoreId={}",
            request.resourcePath,
            explicitId ?: "<generated>",
            dataStoreId ?: "<none>",
        )
        val input = resources.open(request.resourcePath)
        if (input == null) {
            return LogImportResult(
                success = false,
                logId = explicitId,
                message = IMPORT_REJECTED,
                error = "Resource '${request.resourcePath}' not found",
            )
        }
        val result = input.use { importer.import(it, explicitId) }
        if (result.success && dataStoreId != null && result.logId != null) {
            dataStores.attachLog(dataStoreId, result.logId)
        }
        return result
    }
}

data class ImportSampleXesLogRequest(
    val resourcePath: String,
    val logId: String? = null,
    val dataStoreId: String? = null,
)
