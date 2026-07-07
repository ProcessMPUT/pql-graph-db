package com.processm.processminterpreter.xes

import com.processm.processminterpreter.xes.DataStoreRepository
import com.processm.processminterpreter.xes.LogImportResult
import com.processm.processminterpreter.xes.LogRepository
import com.processm.processminterpreter.xes.LogStatistics
import com.processm.processminterpreter.xes.model.Log
import com.processm.processminterpreter.xes.io.ClasspathBundledLogResourceReader
import com.processm.processminterpreter.xes.io.XesLogImporter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

/**
 * Log management: CRUD/search over [LogRepository] plus XES import.
 *
 * Import composition:
 *  1. Reject a duplicate logId / unknown data store up-front (cheap checks).
 *  2. Delegate the heavy lift — XML parse + graph inserts — to [XesLogImporter].
 *
 * Import returns [LogImportResult] directly so callers can distinguish
 * success/failure without catching exceptions; the importer's failure payload
 * passes through unchanged.
 */
@Service
class LogService(
    private val logs: LogRepository,
    private val dataStores: DataStoreRepository,
    private val importer: XesLogImporter,
    private val resources: ClasspathBundledLogResourceReader,
) {
    private companion object {
        const val IMPORT_REJECTED = "Import rejected"
    }

    private val log = LoggerFactory.getLogger(LogService::class.java)

    fun create(request: CreateLogRequest): Log {
        require(request.name.isNotBlank()) { "Log name cannot be blank" }
        val id = request.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        require(!logs.exists(id)) { "Log with ID '$id' already exists" }
        val now = LocalDateTime.now()
        return logs.save(
            Log(
                id = id,
                name = request.name,
                createdAt = now,
                updatedAt = now,
                lifecycleModel = request.lifecycleModel,
                classifiers = request.classifiers,
                extensions = request.extensions,
                traceGlobals = request.traceGlobals,
                eventGlobals = request.eventGlobals,
                customAttributes = request.customAttributes,
            ),
        )
    }

    fun get(id: String): Log = logs.findById(id) ?: throw LogNotFoundException(id)

    fun find(id: String): Log? = logs.findById(id)

    fun statistics(id: String): LogStatistics? = logs.getStatistics(id)

    fun list(): List<Log> = logs.findAll()

    fun listWithStatistics(): List<Pair<Log, LogStatistics>> = logs.getStatisticsAll()

    fun update(request: UpdateLogRequest): Log {
        val existing = logs.findById(request.id) ?: throw LogNotFoundException(request.id)
        val updated = existing.copy(
            name = request.name ?: existing.name,
            updatedAt = LocalDateTime.now(),
            lifecycleModel = request.lifecycleModel ?: existing.lifecycleModel,
            classifiers = request.classifiers ?: existing.classifiers,
            extensions = request.extensions ?: existing.extensions,
            traceGlobals = request.traceGlobals ?: existing.traceGlobals,
            eventGlobals = request.eventGlobals ?: existing.eventGlobals,
            customAttributes = request.customAttributes ?: existing.customAttributes,
        )
        return logs.update(updated)
    }

    fun delete(id: String): Boolean = logs.delete(id)

    fun deleteWithData(id: String): Boolean = logs.deleteWithData(id)

    fun search(request: SearchLogsRequest): List<Log> = when {
        request.namePart != null -> logs.search(request.namePart)
        request.attribute != null -> logs.findByAttribute(request.attribute.key, request.attribute.value)
        request.createdAfter != null && request.createdBefore != null ->
            logs.findCreatedBetween(request.createdAfter, request.createdBefore)
        request.createdAfter != null -> logs.findCreatedAfter(request.createdAfter)
        else -> logs.findAll()
    }

    fun importXes(request: ImportXesLogRequest): LogImportResult {
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

        log.info("Importing XES log id={} dataStoreId={}", explicitId ?: "<generated>", dataStoreId ?: "<none>")
        val result = importer.import(request.input, explicitId)
        if (result.success && dataStoreId != null && result.logId != null) {
            dataStores.attachLog(dataStoreId, result.logId)
        }
        return result
    }

    fun importSample(request: ImportSampleXesLogRequest): LogImportResult {
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
