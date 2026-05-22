package com.processm.processminterpreter.infrastructure.web.processm

import com.processm.processminterpreter.application.datastore.CreateDataStoreRequest
import com.processm.processminterpreter.application.datastore.CreateDataStoreUseCase
import com.processm.processminterpreter.application.datastore.DeleteDataStoreUseCase
import com.processm.processminterpreter.application.datastore.GetDataStoreUseCase
import com.processm.processminterpreter.application.datastore.ListDataStoreLogsUseCase
import com.processm.processminterpreter.application.datastore.ListDataStoresUseCase
import com.processm.processminterpreter.application.datastore.RenameDataStoreUseCase
import com.processm.processminterpreter.application.log.DeleteLogUseCase
import com.processm.processminterpreter.application.log.ImportXesLogRequest
import com.processm.processminterpreter.application.log.ImportXesLogUseCase
import com.processm.processminterpreter.application.query.ExecutePqlQueryRequest
import com.processm.processminterpreter.application.query.ExecutePqlQueryUseCase
import com.processm.processminterpreter.application.query.ExportQueryAsXesRequest
import com.processm.processminterpreter.application.query.ExportQueryAsXesUseCase
import com.processm.processminterpreter.application.ports.DataStoreNotFoundException
import com.processm.processminterpreter.application.processm.QueryJsonProjection
import com.processm.processminterpreter.application.processm.ProcessMXesJsonFormatter
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.infrastructure.config.ProcessMConfig
import com.processm.processminterpreter.infrastructure.web.processm.dto.ProcessMDataStoreRequest
import com.processm.processminterpreter.infrastructure.web.processm.dto.ProcessMDataStoreLogSummaryResponse
import com.processm.processminterpreter.infrastructure.web.processm.dto.ProcessMDataStoreResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartHttpServletRequest
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RestController
@RequestMapping("/api/data-stores")
class ProcessMDataStoreController(
    private val createDataStore: CreateDataStoreUseCase,
    private val listDataStores: ListDataStoresUseCase,
    private val listDataStoreLogs: ListDataStoreLogsUseCase,
    private val getDataStore: GetDataStoreUseCase,
    private val renameDataStore: RenameDataStoreUseCase,
    private val deleteDataStore: DeleteDataStoreUseCase,
    private val deleteLog: DeleteLogUseCase,
    private val importXesLog: ImportXesLogUseCase,
    private val executeQuery: ExecutePqlQueryUseCase,
    private val exportQueryAsXes: ExportQueryAsXesUseCase,
    private val formatter: ProcessMXesJsonFormatter,
    private val processMConfig: ProcessMConfig,
) {

    @PostMapping
    fun create(@RequestBody request: ProcessMDataStoreRequest): ResponseEntity<ProcessMDataStoreResponse> {
        val dataStore = createDataStore.create(CreateDataStoreRequest(name = request.name))
        return ResponseEntity.status(HttpStatus.CREATED).body(ProcessMDataStoreResponse.from(dataStore))
    }

    @GetMapping
    fun list(): ResponseEntity<List<ProcessMDataStoreResponse>> =
        ResponseEntity.ok(listDataStores.list().map { ProcessMDataStoreResponse.from(it) })

    @GetMapping("/{dataStoreId}")
    fun get(@PathVariable dataStoreId: String): ResponseEntity<ProcessMDataStoreResponse> =
        ResponseEntity.ok(ProcessMDataStoreResponse.from(getDataStore.get(dataStoreId)))

    @GetMapping("/{dataStoreId}/log-summaries")
    fun logSummaries(@PathVariable dataStoreId: String): ResponseEntity<List<ProcessMDataStoreLogSummaryResponse>> =
        ResponseEntity.ok(listDataStoreLogs.list(dataStoreId).map { ProcessMDataStoreLogSummaryResponse.from(it) })

    @PatchMapping("/{dataStoreId}")
    fun rename(
        @PathVariable dataStoreId: String,
        @RequestBody request: ProcessMDataStoreRequest,
    ): ResponseEntity<Unit> {
        renameDataStore.rename(dataStoreId, request.name)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{dataStoreId}")
    fun delete(@PathVariable dataStoreId: String): ResponseEntity<Unit> =
        if (deleteDataStore.delete(dataStoreId)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()

    @PostMapping("/{dataStoreId}/logs", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadLog(
        @PathVariable dataStoreId: String,
        request: MultipartHttpServletRequest,
    ): ResponseEntity<Any> {
        getDataStore.get(dataStoreId)
        val file = request.fileMap.values.firstOrNull()
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "Expected a multipart file"))
        val result = importXesLog.import(
            ImportXesLogRequest(
                input = file.inputStream,
                logId = null,
                dataStoreId = dataStoreId,
            ),
        )
        return if (result.success) {
            ResponseEntity.status(HttpStatus.CREATED).build()
        } else {
            ResponseEntity.badRequest().body(mapOf("error" to (result.error ?: result.message)))
        }
    }

    @GetMapping("/{dataStoreId}/logs")
    fun queryLogs(
        @PathVariable dataStoreId: String,
        @RequestParam(defaultValue = "") query: String,
        @RequestParam(required = false) includeTraces: Boolean?,
        @RequestParam(required = false) includeEvents: Boolean?,
        @RequestHeader(HttpHeaders.ACCEPT, required = false) accept: String?,
    ): ResponseEntity<Any> {
        getDataStore.get(dataStoreId)
        val resolvedIncludeEvents = includeEvents ?: true
        val resolvedIncludeTraces = resolvedIncludeEvents || (includeTraces ?: true)
        val requestedType = resolveAcceptedContentType(accept)
        return if (requestedType == "application/zip") {
            ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"xes.zip\"")
                .body(exportZip(dataStoreId, query))
        } else {
            val result = executeQuery.execute(
                ExecutePqlQueryRequest(
                    query = query,
                    dataStoreId = dataStoreId,
                    defaultLimits = processMConfig.defaultLimits.toHierarchicalLimits(),
                    materializedScopes = requestedScopes(resolvedIncludeTraces, resolvedIncludeEvents),
                ),
            )
            val json = formatter.formatAsXesJson(
                QueryJsonProjection(
                    logs = result.logs,
                    rows = result.rows,
                    hasExplicitSelect = result.hasExplicitSelect,
                    selectAllScopes = result.selectAllScopes,
                    projectedTraceStandardAttributes = result.projectedTraceStandardAttributes,
                    includeTraces = resolvedIncludeTraces,
                    includeEvents = resolvedIncludeEvents,
                ),
            )
            ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(json)
        }
    }

    @DeleteMapping("/{dataStoreId}/logs/{logId}")
    fun deleteLog(
        @PathVariable dataStoreId: String,
        @PathVariable logId: String,
    ): ResponseEntity<Unit> {
        val belongsToStore = listDataStoreLogs.list(dataStoreId).any { it.logId == logId }
        if (!belongsToStore) {
            return ResponseEntity.notFound().build()
        }

        return if (deleteLog.deleteWithData(logId)) ResponseEntity.noContent().build()
        else ResponseEntity.notFound().build()
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(DataStoreNotFoundException::class)
    fun dataStoreNotFound(): ResponseEntity<Map<String, String>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to "Data store not found"))

    private fun exportZip(dataStoreId: String, query: String): ByteArray {
        val xes = ByteArrayOutputStream()
        exportQueryAsXes.export(
            ExportQueryAsXesRequest(
                query = query,
                dataStoreId = dataStoreId,
                compress = false,
                defaultLimits = processMConfig.defaultLimits.toHierarchicalLimits(),
            ),
            xes,
        )

        val zipped = ByteArrayOutputStream()
        ZipOutputStream(zipped).use { zip ->
            zip.putNextEntry(ZipEntry("xes.xml"))
            zip.write(xes.toByteArray())
            zip.closeEntry()
        }
        return zipped.toByteArray()
    }

    private fun resolveAcceptedContentType(accept: String?): String =
        accept
            ?.split(',')
            ?.map { it.substringBefore(';').trim() }
            ?.firstOrNull { it == MediaType.APPLICATION_JSON_VALUE || it == "application/zip" }
            ?: MediaType.APPLICATION_JSON_VALUE

    private fun requestedScopes(includeTraces: Boolean, includeEvents: Boolean): Set<Scope> =
        buildSet {
            add(Scope.LOG)
            if (includeTraces || includeEvents) add(Scope.TRACE)
            if (includeEvents) add(Scope.EVENT)
        }
}
