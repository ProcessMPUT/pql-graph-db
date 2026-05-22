package com.processm.processminterpreter.application.log

import com.processm.processminterpreter.application.ports.LogDataImporter
import com.processm.processminterpreter.application.ports.LogImportResult
import com.processm.processminterpreter.application.ports.LogRepository
import com.processm.processminterpreter.application.ports.DataStoreRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.InputStream

/**
 * Application-layer entry point for uploading an XES file.
 *
 * Composition:
 *  1. Reject a duplicate [logId] up-front via [LogRepository.exists] (cheap).
 *  2. Delegate the heavy lift — XML parse + graph inserts — to [LogDataImporter].
 *
 * Returns [LogImportResult] directly so callers can distinguish success/failure
 * without catching exceptions. The underlying importer's failure payload is
 * passed through unchanged; the use case only owns the pre-flight duplicate check.
 */
@Component
class ImportXesLogUseCase(
    private val importer: LogDataImporter,
    private val logs: LogRepository,
    private val dataStores: DataStoreRepository,
) {

    private val log = LoggerFactory.getLogger(ImportXesLogUseCase::class.java)

    fun import(request: ImportXesLogRequest): LogImportResult {
        val explicitId = request.logId?.takeIf { it.isNotBlank() }
        if (explicitId != null && logs.exists(explicitId)) {
            return LogImportResult(
                success = false,
                logId = explicitId,
                message = "Import rejected",
                error = "Log with ID '$explicitId' already exists",
            )
        }
        val dataStoreId = request.dataStoreId?.takeIf { it.isNotBlank() }
        if (dataStoreId != null && !dataStores.exists(dataStoreId)) {
            return LogImportResult(
                success = false,
                logId = explicitId,
                message = "Import rejected",
                error = "Data store with ID '$dataStoreId' does not exist",
            )
        }

        log.info("Importing XES log id={} dataStoreId={}", explicitId ?: "<generated>", dataStoreId ?: "<none>")
        val result = importer.import(request.input, explicitId)
        if (result.success && dataStoreId != null && result.logId != null) {
            dataStores.attachLog(dataStoreId, result.logId)
        }
        return result
    }
}

data class ImportXesLogRequest(
    val input: InputStream,
    val logId: String? = null,
    val dataStoreId: String? = null,
)
