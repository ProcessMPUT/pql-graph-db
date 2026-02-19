package com.processm.processminterpreter.controller

import com.processm.processminterpreter.dto.CreateLogRequest
import com.processm.processminterpreter.dto.DeleteResponse
import com.processm.processminterpreter.dto.ErrorResponse
import com.processm.processminterpreter.dto.LogResponse
import com.processm.processminterpreter.dto.LogSearchRequest
import com.processm.processminterpreter.dto.LogWithStatisticsResponse
import com.processm.processminterpreter.dto.UpdateLogRequest
import com.processm.processminterpreter.dto.XESUploadResponse
import com.processm.processminterpreter.service.LogNotFoundException
import com.processm.processminterpreter.service.LogService
import com.processm.processminterpreter.xes.XESLoader
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * REST Controller for log management operations
 *
 * Provides HTTP endpoints for CRUD operations on process logs
 */
@RestController
@RequestMapping("/api/logs")
@CrossOrigin(origins = ["*"])
class LogController(
    private val logService: LogService,
    private val xesLoader: XESLoader,
) {
    private val logger = LoggerFactory.getLogger(LogController::class.java)

    /**
     * Upload XES file
     * POST /api/logs/upload
     */
    @PostMapping("/upload")
    fun uploadXESFile(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("logId", required = false) logId: String?,
    ): ResponseEntity<XESUploadResponse> {
        logger.info("Uploading XES file: ${file.originalFilename}, size: ${file.size} bytes")

        return try {
            // Validate file
            if (file.isEmpty) {
                return ResponseEntity.badRequest().body(
                    XESUploadResponse(
                        success = false,
                        message = "File is empty",
                        error = "No file content provided",
                    ),
                )
            }

            val filename = file.originalFilename ?: ""
            val isXES = filename.endsWith(".xes", ignoreCase = true)
            val isGzippedXES = filename.endsWith(".xes.gz", ignoreCase = true) || filename.endsWith(".xes.gzip", ignoreCase = true)

            if (!isXES && !isGzippedXES) {
                return ResponseEntity.badRequest().body(
                    XESUploadResponse(
                        success = false,
                        message = "Invalid file format",
                        error = "Only XES files (.xes, .xes.gz) are supported",
                    ),
                )
            }

            // Handle gzip decompression if needed
            val inputStream = if (isGzippedXES) {
                logger.info("Decompressing gzipped XES file: $filename")
                java.util.zip.GZIPInputStream(file.inputStream)
            } else {
                file.inputStream
            }

            // Load XES file
            val result = xesLoader.loadXESFile(inputStream, logId)

            if (result.success) {
                ResponseEntity.ok(
                    XESUploadResponse(
                        success = true,
                        logId = result.logId,
                        tracesCount = result.tracesCount,
                        eventsCount = result.eventsCount,
                        message = result.message,
                        filename = file.originalFilename,
                    ),
                )
            } else {
                ResponseEntity.badRequest().body(
                    XESUploadResponse(
                        success = false,
                        message = result.message,
                        error = result.error,
                        filename = file.originalFilename,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.error("Unexpected error uploading XES file", e)
            ResponseEntity.internalServerError().body(
                XESUploadResponse(
                    success = false,
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
     */
    @PostMapping("/load-sample")
    fun loadSampleXES(
        @RequestParam(
            "resourcePath",
            defaultValue = "logs/sample_process.xes",
        ) resourcePath: String,
        @RequestParam("logId", required = false) logId: String?,
    ): ResponseEntity<XESUploadResponse> {
        logger.info("Loading sample XES from resource: $resourcePath")

        return try {
            val result = xesLoader.loadXESFromResource(resourcePath, logId)

            if (result.success) {
                ResponseEntity.ok(
                    XESUploadResponse(
                        success = true,
                        logId = result.logId,
                        tracesCount = result.tracesCount,
                        eventsCount = result.eventsCount,
                        message = result.message,
                        filename = resourcePath,
                    ),
                )
            } else {
                ResponseEntity.badRequest().body(
                    XESUploadResponse(
                        success = false,
                        message = result.message,
                        error = result.error,
                        filename = resourcePath,
                    ),
                )
            }
        } catch (e: Exception) {
            logger.error("Unexpected error loading sample XES", e)
            ResponseEntity.internalServerError().body(
                XESUploadResponse(
                    success = false,
                    message = "Failed to load sample XES",
                    error = "Internal server error: ${e.message}",
                    filename = resourcePath,
                ),
            )
        }
    }

    /**
     * Create a new log
     * POST /api/logs
     */
    @PostMapping
    fun createLog(
        @RequestBody request: CreateLogRequest,
    ): ResponseEntity<LogResponse> {
        logger.info("Creating new log: ${request.logId}")

        return try {
            val log = logService.createLog(
                logId = request.logId,
                name = request.name,
                attributes = request.attributes,
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
    @GetMapping("/{logId}")
    fun getLog(
        @PathVariable logId: String,
    ): ResponseEntity<LogResponse> {
        logger.debug("Retrieving log: $logId")

        return try {
            val log = logService.getLogById(logId)
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
    @GetMapping("/{logId}/statistics")
    fun getLogWithStatistics(
        @PathVariable logId: String,
    ): ResponseEntity<LogWithStatisticsResponse> {
        logger.debug("Retrieving log statistics: $logId")

        return try {
            val logStats = logService.getLogWithStatistics(logId)
            ResponseEntity.ok(LogWithStatisticsResponse.from(logStats))
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
    @GetMapping
    fun getAllLogs(
        @RequestParam(defaultValue = "false") includeStatistics: Boolean,
    ): ResponseEntity<List<*>> {
        logger.debug("Retrieving all logs (includeStatistics: $includeStatistics)")

        return try {
            if (includeStatistics) {
                val logsWithStats = logService.getAllLogsWithStatistics()
                ResponseEntity.ok(logsWithStats.map { LogWithStatisticsResponse.from(it) })
            } else {
                val logs = logService.getAllLogs()
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
    @PostMapping("/search")
    fun searchLogs(
        @RequestBody request: LogSearchRequest,
    ): ResponseEntity<List<LogResponse>> {
        logger.debug("Searching logs with criteria: {}", request)

        return try {
            val logs = when {
                !request.name.isNullOrBlank() -> {
                    logService.searchLogsByName(request.name)
                }

                request.createdAfter != null && request.createdBefore != null -> {
                    logService.getLogsCreatedBetween(request.createdAfter, request.createdBefore)
                }

                request.createdAfter != null -> {
                    logService.getLogsCreatedAfter(request.createdAfter)
                }

                request.attributeKey != null && request.attributeValue != null -> {
                    logService.findLogsByAttribute(request.attributeKey, request.attributeValue)
                }

                else -> {
                    logService.getAllLogs()
                }
            }

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
    @PutMapping("/{logId}")
    fun updateLog(
        @PathVariable logId: String,
        @RequestBody request: UpdateLogRequest,
    ): ResponseEntity<LogResponse> {
        logger.info("Updating log: $logId")

        return try {
            val updatedLog = logService.updateLog(
                logId = logId,
                name = request.name,
                attributes = request.attributes,
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
    @DeleteMapping("/{logId}")
    fun deleteLog(
        @PathVariable logId: String,
        @RequestParam(defaultValue = "false") deleteAllData: Boolean,
    ): ResponseEntity<DeleteResponse> {
        logger.info("Deleting log: $logId (deleteAllData: $deleteAllData)")

        return try {
            val success = if (deleteAllData) {
                logService.deleteLogWithAllData(logId)
            } else {
                logService.deleteLog(logId)
            }

            if (success) {
                ResponseEntity.ok(
                    DeleteResponse(
                        success = true,
                        message = "Log deleted successfully",
                        deletedLogId = logId,
                    ),
                )
            } else {
                ResponseEntity.internalServerError().body(
                    DeleteResponse(
                        success = false,
                        message = "Failed to delete log",
                        deletedLogId = logId,
                    ),
                )
            }
        } catch (e: LogNotFoundException) {
            logger.warn("Log not found for deletion: $logId")
            ResponseEntity.notFound().build()
        } catch (e: Exception) {
            logger.error("Unexpected error deleting log: $logId", e)
            ResponseEntity.internalServerError().build()
        }
    }

    /**
     * Check if log exists
     * HEAD /api/logs/{logId}
     */
    @RequestMapping(value = ["/{logId}"], method = [RequestMethod.HEAD])
    fun logExists(
        @PathVariable logId: String,
    ): ResponseEntity<Unit> {
        logger.debug("Checking if log exists: $logId")

        return try {
            val exists = logService.logExists(logId)
            if (exists) {
                ResponseEntity.ok().build()
            } else {
                ResponseEntity.notFound().build()
            }
        } catch (e: Exception) {
            logger.error("Unexpected error checking log existence: $logId", e)
            ResponseEntity.internalServerError().build()
        }
    }

    /**
     * Generate unique log ID
     * GET /api/logs/generate-id
     */
    @GetMapping("/generate-id")
    fun generateLogId(): ResponseEntity<Map<String, String>> {
        logger.debug("Generating unique log ID")

        return try {
            val logId = logService.generateLogId()
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

        val errorResponse = ErrorResponse(
            error = e.javaClass.simpleName,
            message = e.message ?: "An unexpected error occurred",
        )

        return ResponseEntity.internalServerError().body(errorResponse)
    }
}
