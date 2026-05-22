package com.processm.processminterpreter.infrastructure.web.log

import com.processm.processminterpreter.application.log.CreateLogUseCase
import com.processm.processminterpreter.application.log.DeleteLogUseCase
import com.processm.processminterpreter.application.log.GetLogUseCase
import com.processm.processminterpreter.application.log.ImportXesLogRequest
import com.processm.processminterpreter.application.log.ImportXesLogUseCase
import com.processm.processminterpreter.application.log.ImportSampleXesLogRequest
import com.processm.processminterpreter.application.log.ImportSampleXesLogUseCase
import com.processm.processminterpreter.application.log.ListLogsUseCase
import com.processm.processminterpreter.application.log.LogNotFoundException
import com.processm.processminterpreter.application.log.SearchLogsRequest
import com.processm.processminterpreter.application.log.SearchLogsUseCase
import com.processm.processminterpreter.application.log.UpdateLogUseCase
import com.processm.processminterpreter.application.log.AttributeFilter
import com.processm.processminterpreter.application.log.CreateLogRequest as CreateLogCommand
import com.processm.processminterpreter.application.log.UpdateLogRequest as UpdateLogCommand
import com.processm.processminterpreter.application.ports.LogStatistics as PortLogStatistics
import com.processm.processminterpreter.infrastructure.web.common.dto.ErrorResponse
import com.processm.processminterpreter.infrastructure.web.log.dto.CreateLogRequest
import com.processm.processminterpreter.infrastructure.web.log.dto.DeleteResponse
import com.processm.processminterpreter.infrastructure.web.log.dto.LogResponse
import com.processm.processminterpreter.infrastructure.web.log.dto.LogSearchRequest
import com.processm.processminterpreter.infrastructure.web.log.dto.LogWithStatisticsResponse
import com.processm.processminterpreter.infrastructure.web.log.dto.SampleLogResourceResponse
import com.processm.processminterpreter.infrastructure.web.log.dto.UpdateLogRequest
import com.processm.processminterpreter.infrastructure.web.log.dto.XESUploadResponse
import org.slf4j.LoggerFactory
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * REST Controller for log management operations.
 *
 * Thin adapter: each endpoint forwards to the application-layer use case that
 * owns that specific behavior. Controllers stay focused on HTTP concerns
 * (status codes, multipart parsing, exception-to-response mapping).
 *
 */
@RestController
@RequestMapping("/api/logs")
@CrossOrigin(origins = ["*"])
class LogController(
    // Field names end in `UseCase` so they don't shadow the endpoint methods
    // below, which share the short verbs (createLog, updateLog, deleteLog…).
    private val createLogUseCase: CreateLogUseCase,
    private val getLogUseCase: GetLogUseCase,
    private val listLogsUseCase: ListLogsUseCase,
    private val updateLogUseCase: UpdateLogUseCase,
    private val deleteLogUseCase: DeleteLogUseCase,
    private val searchLogsUseCase: SearchLogsUseCase,
    private val importXesUseCase: ImportXesLogUseCase,
    private val importSampleXesUseCase: ImportSampleXesLogUseCase,
) : LogApi {
    private val logger = LoggerFactory.getLogger(LogController::class.java)

    /**
     * Upload XES file
     * POST /api/logs/upload
     */
    override fun uploadXESFile(
        file: org.springframework.web.multipart.MultipartFile,
        logId: String?,
    ): ResponseEntity<XESUploadResponse> {
        logger.info("Uploading XES file: ${file.originalFilename}, size: ${file.size} bytes")

        return try {
            if (file.isEmpty) {
                return ResponseEntity.badRequest().body(
                    XESUploadResponse.internalError(
                        message = "File is empty",
                        error = "No file content provided",
                        filename = file.originalFilename,
                    ),
                )
            }

            val filename = file.originalFilename ?: ""
            val isXES = filename.endsWith(".xes", ignoreCase = true)
            val isGzippedXES = filename.endsWith(".xes.gz", ignoreCase = true) ||
                filename.endsWith(".xes.gzip", ignoreCase = true)

            if (!isXES && !isGzippedXES) {
                return ResponseEntity.badRequest().body(
                    XESUploadResponse.internalError(
                        message = "Invalid file format",
                        error = "Only XES files (.xes, .xes.gz) are supported",
                        filename = file.originalFilename,
                    ),
                )
            }

            // Gzip decompression happens here — importer sees a plain XES stream.
            val inputStream =
                if (isGzippedXES) {
                    logger.info("Decompressing gzipped XES file: $filename")
                    java.util.zip.GZIPInputStream(file.inputStream)
                } else {
                    file.inputStream
                }

            val result = importXesUseCase.import(ImportXesLogRequest(input = inputStream, logId = logId))

            if (result.success) ResponseEntity.ok(XESUploadResponse.from(result, file.originalFilename))
            else ResponseEntity.badRequest().body(XESUploadResponse.from(result, file.originalFilename))
        } catch (e: Exception) {
            logger.error("Unexpected error uploading XES file", e)
            ResponseEntity.internalServerError().body(
                XESUploadResponse.internalError(
                    message = "Failed to upload XES file",
                    error = "Internal server error: ${e.message}",
                    filename = file.originalFilename,
                ),
            )
        }
    }

    /**
     * Load XES file from resources
     * POST /api/logs/load-sample
     *
     * Uses the same application-layer import flow as upload, but with a
     * resource-backed importer behind the port.
     */
    override fun loadSampleXES(
        resourcePath: String,
        logId: String?,
        dataStoreId: String?,
    ): ResponseEntity<XESUploadResponse> {
        logger.info("Loading sample XES from resource: $resourcePath")

        return try {
            val result =
                importSampleXesUseCase.import(
                    ImportSampleXesLogRequest(
                        resourcePath = resourcePath,
                        logId = logId,
                        dataStoreId = dataStoreId,
                    ),
                )

            if (result.success) ResponseEntity.ok(XESUploadResponse.from(result, resourcePath))
            else ResponseEntity.badRequest().body(XESUploadResponse.from(result, resourcePath))
        } catch (e: Exception) {
            logger.error("Unexpected error loading sample XES", e)
            ResponseEntity.internalServerError().body(
                XESUploadResponse.internalError(
                    message = "Failed to load sample XES",
                    error = "Internal server error: ${e.message}",
                    filename = resourcePath,
                ),
            )
        }
    }

    override fun listSampleXES(): ResponseEntity<List<SampleLogResourceResponse>> {
        val resolver = PathMatchingResourcePatternResolver()
        val samples =
            resolver.getResources("classpath*:logs/*.xes.gz")
                .mapNotNull { resource ->
                    val filename = resource.filename ?: return@mapNotNull null
                    SampleLogResourceResponse(
                        name = filename.removeSuffix(".xes.gz"),
                        resourcePath = "logs/$filename",
                    )
                }
                .sortedBy { it.name.lowercase() }
        return ResponseEntity.ok(samples)
    }

    /**
     * Create a new log
     * POST /api/logs
     */
    override fun createLog(
        request: CreateLogRequest,
    ): ResponseEntity<LogResponse> {
        logger.info("Creating new log: ${request.logId}")

        return try {
            val log =
                createLogUseCase.create(
                    CreateLogCommand(
                        id = request.logId,
                        name = request.name,
                        // Wire type is `Map<String, Any>`; domain uses `Map<String, Any?>`.
                        customAttributes = request.attributes,
                    ),
                )
            ResponseEntity.status(HttpStatus.CREATED).body(LogResponse.from(log))
        } catch (e: IllegalArgumentException) {
            logger.warn("Failed to create log: ${e.message}")
            ResponseEntity.badRequest().build()
        } catch (e: Exception) {
            logger.error("Unexpected error creating log", e)
            ResponseEntity.internalServerError().build()
        }
    }

    /**
     * Get log by ID
     * GET /api/logs/{logId}
     */
    override fun getLog(
        logId: String,
    ): ResponseEntity<LogResponse> {
        logger.debug("Retrieving log: $logId")

        return try {
            val log = getLogUseCase.get(logId)
            ResponseEntity.ok(LogResponse.from(log))
        } catch (e: LogNotFoundException) {
            logger.warn("Log not found: $logId")
            ResponseEntity.notFound().build()
        } catch (e: Exception) {
            logger.error("Unexpected error retrieving log: $logId", e)
            ResponseEntity.internalServerError().build()
        }
    }

    /**
     * Get log with statistics
     * GET /api/logs/{logId}/statistics
     */
    override fun getLogWithStatistics(
        logId: String,
    ): ResponseEntity<LogWithStatisticsResponse> {
        logger.debug("Retrieving log statistics: $logId")

        return try {
            val log = getLogUseCase.get(logId)
            // Port returns null when the backend hasn't materialized counters yet —
            // emit zeros so the wire shape stays stable.
            val stats = getLogUseCase.statistics(logId) ?: PortLogStatistics(traceCount = 0, eventCount = 0)
            ResponseEntity.ok(LogWithStatisticsResponse.from(log, stats))
        } catch (e: LogNotFoundException) {
            logger.warn("Log not found: $logId")
            ResponseEntity.notFound().build()
        } catch (e: Exception) {
            logger.error("Unexpected error retrieving log statistics: $logId", e)
            ResponseEntity.internalServerError().build()
        }
    }

    /**
     * Get all logs
     * GET /api/logs
     */
    override fun getAllLogs(
        includeStatistics: Boolean,
    ): ResponseEntity<List<*>> {
        logger.debug("Retrieving all logs (includeStatistics: $includeStatistics)")

        return try {
            if (includeStatistics) {
                val logsWithStats = listLogsUseCase.listWithStatistics()
                ResponseEntity.ok(logsWithStats.map { (log, stats) -> LogWithStatisticsResponse.from(log, stats) })
            } else {
                val logs = listLogsUseCase.list()
                ResponseEntity.ok(logs.map { LogResponse.from(it) })
            }
        } catch (e: Exception) {
            logger.error("Unexpected error retrieving all logs", e)
            ResponseEntity.internalServerError().build()
        }
    }

    /**
     * Search logs
     * POST /api/logs/search
     */
    override fun searchLogs(
        request: LogSearchRequest,
    ): ResponseEntity<List<LogResponse>> {
        logger.debug("Searching logs with criteria: {}", request)

        return try {
            val searchRequest = SearchLogsRequest(
                namePart = request.name?.takeIf { it.isNotBlank() },
                attribute = if (request.attributeKey != null && request.attributeValue != null) {
                    AttributeFilter(request.attributeKey, request.attributeValue)
                } else null,
                createdAfter = request.createdAfter,
                createdBefore = request.createdBefore,
            )
            val logs = searchLogsUseCase.search(searchRequest)
            ResponseEntity.ok(logs.map { LogResponse.from(it) })
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid search criteria: ${e.message}")
            ResponseEntity.badRequest().build()
        } catch (e: Exception) {
            logger.error("Unexpected error searching logs", e)
            ResponseEntity.internalServerError().build()
        }
    }

    /**
     * Update log
     * PUT /api/logs/{logId}
     */
    override fun updateLog(
        logId: String,
        request: UpdateLogRequest,
    ): ResponseEntity<LogResponse> {
        logger.info("Updating log: $logId")

        return try {
            val updatedLog = updateLogUseCase.update(
                UpdateLogCommand(
                    id = logId,
                    name = request.name,
                    customAttributes = request.attributes,
                ),
            )
            ResponseEntity.ok(LogResponse.from(updatedLog))
        } catch (e: LogNotFoundException) {
            logger.warn("Log not found for update: $logId")
            ResponseEntity.notFound().build()
        } catch (e: IllegalArgumentException) {
            logger.warn("Invalid update request for log $logId: ${e.message}")
            ResponseEntity.badRequest().build()
        } catch (e: Exception) {
            logger.error("Unexpected error updating log: $logId", e)
            ResponseEntity.internalServerError().build()
        }
    }

    /**
     * Delete log
     * DELETE /api/logs/{logId}
     */
    override fun deleteLog(
        logId: String,
        deleteAllData: Boolean,
    ): ResponseEntity<DeleteResponse> {
        logger.info("Deleting log: $logId (deleteAllData: $deleteAllData)")

        return try {
            val success =
                if (deleteAllData) deleteLogUseCase.deleteWithData(logId) else deleteLogUseCase.delete(logId)

            if (success) {
                ResponseEntity.ok(
                    DeleteResponse(
                        success = true,
                        message = "Log deleted successfully",
                        deletedLogId = logId,
                    ),
                )
            } else {
                // Legacy contract: 404 when the log simply wasn't there.
                ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    DeleteResponse(
                        success = false,
                        message = "Log not found",
                        deletedLogId = logId,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.error("Unexpected error deleting log: $logId", e)
            ResponseEntity.internalServerError().body(
                DeleteResponse(
                    success = false,
                    message = "Failed to delete log: ${e.message ?: "internal server error"}",
                    deletedLogId = logId,
                ),
            )
        }
    }

    /**
     * Check if log exists
     * HEAD /api/logs/{logId}
     */
    override fun logExists(
        logId: String,
    ): ResponseEntity<Unit> {
        logger.debug("Checking if log exists: $logId")

        return try {
            // `find` is the non-throwing variant — we want a simple 200/404 here, not
            // an exception handler kicking in on the throwing `get`.
            val exists = getLogUseCase.find(logId) != null
            if (exists) ResponseEntity.ok().build() else ResponseEntity.notFound().build()
        } catch (e: Exception) {
            logger.error("Unexpected error checking log existence: $logId", e)
            ResponseEntity.internalServerError().build()
        }
    }

    /**
     * Generate unique log ID
     * GET /api/logs/generate-id
     */
    override fun generateLogId(): ResponseEntity<Map<String, String>> {
        logger.debug("Generating unique log ID")

        return try {
            // Same shape as legacy `LogService.generateLogId()` — short UUID prefix.
            // Uniqueness is best-effort (collision chance ~2^-32 per generation).
            val logId = "log-${UUID.randomUUID().toString().substring(0, 8)}"
            ResponseEntity.ok(mapOf("logId" to logId))
        } catch (e: Exception) {
            logger.error("Unexpected error generating log ID", e)
            ResponseEntity.internalServerError().build()
        }
    }

    /**
     * Global exception handler for this controller
     */
    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ErrorResponse> {
        logger.error("Unhandled exception in LogController", e)

        val errorResponse =
            ErrorResponse(
                error = e.javaClass.simpleName,
                message = e.message ?: "An unexpected error occurred",
            )

        return ResponseEntity.internalServerError().body(errorResponse)
    }
}
