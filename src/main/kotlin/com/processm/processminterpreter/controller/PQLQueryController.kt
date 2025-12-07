package com.processm.processminterpreter.controller

import com.processm.processminterpreter.dto.ErrorResponse
import com.processm.processminterpreter.service.PQLQueryService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import com.processm.processminterpreter.service.RemoteProcessMService

/**
 * REST Controller for PQL query operations
 *
 * Provides HTTP endpoints for executing and validating PQL queries
 */
@RestController
@RequestMapping("/api/query")
@CrossOrigin(origins = ["*"])
class PQLQueryController(
    private val pqlQueryService: PQLQueryService,
    private val remoteProcessMService: RemoteProcessMService,
) {
    private val logger = LoggerFactory.getLogger(PQLQueryController::class.java)

    /**
     * Execute PQL query
     * POST /api/query/execute
     */
    @PostMapping("/execute")
    fun executeQuery(
        @RequestBody request: PQLQueryRequest,
    ): ResponseEntity<PQLQueryResponse> {
        logger.info("=== Executing PQL Query ===")
        logger.info("PQL: ${request.query}")
        logger.info("LogId: ${request.logId ?: "ALL"}")

        return try {
            val result = pqlQueryService.executePQLQuery(request.query, request.logId)

            logger.info("Generated Cypher: ${result.cypherQuery}")
            logger.info("Execution time: ${result.executionTimeMs}ms")
            logger.info("Results count: ${result.resultCount}")

            if (!result.success) {
                logger.warn("Query failed: ${result.error}")
            }

            val response =
                PQLQueryResponse(
                    success = result.success,
                    query = result.query,
                    cypherQuery = result.cypherQuery,
                    results = result.results,
                    resultCount = result.resultCount,
                    executionTimeMs = result.executionTimeMs,
                    error = result.error,
                    timestamp = LocalDateTime.now(),
                )

            if (result.success) {
                ResponseEntity.ok(response)
            } else {
                ResponseEntity.badRequest().body(response)
            }
        } catch (e: Exception) {
            logger.error("Unexpected error executing PQL query", e)
            ResponseEntity.internalServerError().body(
                PQLQueryResponse(
                    success = false,
                    query = request.query,
                    error = "Internal server error: ${e.message}",
                    results = emptyList(),
                    resultCount = 0,
                    executionTimeMs = 0,
                    timestamp = LocalDateTime.now(),
                ),
            )
        }
    }

    /**
     * Execute PQL query and return results as XES format
     * POST /api/query/execute-xes
     *
     * @param request PQL query request containing query and optional logId
     * @param compress Whether to gzip compress the output (default: false)
     * @param logName Optional log name for the XES output (default: "Query Result Log")
     * @return XES XML file (or gzipped XES if compress=true)
     */
    @PostMapping("/execute-xes")
    fun executeQueryAsXES(
        @RequestBody request: PQLQueryRequest,
        @RequestParam(defaultValue = "false") compress: Boolean,
        @RequestParam(defaultValue = "Query Result Log") logName: String,
    ): ResponseEntity<ByteArray> {
        logger.info("=== Executing PQL Query as XES ===")
        logger.info("PQL: ${request.query}")
        logger.info("LogId: ${request.logId ?: "ALL"}")
        logger.info("Compress: $compress")
        logger.info("LogName: $logName")

        return try {
            val xesBytes = pqlQueryService.executePQLQueryAsXES(
                pqlQuery = request.query,
                logId = request.logId,
                compress = compress,
                logName = logName,
            )

            // Generate filename with timestamp
            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val extension = if (compress) "xes.gz" else "xes"
            val filename = "query_result_$timestamp.$extension"

            // Set appropriate Content-Type and headers
            val contentType = if (compress) {
                MediaType.parseMediaType("application/gzip")
            } else {
                MediaType.parseMediaType("application/xml")
            }

            val headers = HttpHeaders().apply {
                this.contentType = contentType
                this.contentDisposition = org.springframework.http.ContentDisposition
                    .attachment()
                    .filename(filename)
                    .build()
                this.contentLength = xesBytes.size.toLong()
            }

            logger.info("XES output generated: ${xesBytes.size} bytes, filename: $filename")
            ResponseEntity.ok()
                .headers(headers)
                .body(xesBytes)
        } catch (e: Exception) {
            logger.error("Error executing PQL query as XES", e)

            // Return error as plain text since we can't return JSON for this endpoint
            val errorMessage = "Error executing query as XES: ${e.message}"
            ResponseEntity.internalServerError()
                .contentType(MediaType.TEXT_PLAIN)
                .body(errorMessage.toByteArray())
        }
    }

    /**
     * Validate PQL query syntax
     * POST /api/query/validate
     */
    @PostMapping("/validate")
    fun validateQuery(
        @RequestBody request: PQLValidationRequest,
    ): ResponseEntity<PQLValidationResponse> {
        logger.debug("Validating PQL query: ${request.query}")

        return try {
            val result = pqlQueryService.validatePQLQuery(request.query)

            val response =
                PQLValidationResponse(
                    valid = result.valid,
                    query = result.query,
                    cypherQuery = result.cypherQuery,
                    message = result.message,
                    error = result.error,
                    timestamp = LocalDateTime.now(),
                )

            ResponseEntity.ok(response)
        } catch (e: Exception) {
            logger.error("Unexpected error validating PQL query", e)
            ResponseEntity.internalServerError().body(
                PQLValidationResponse(
                    valid = false,
                    query = request.query,
                    message = "Validation failed",
                    error = "Internal server error: ${e.message}",
                    timestamp = LocalDateTime.now(),
                ),
            )
        }
    }

    /**
     * Get query execution statistics
     * GET /api/query/statistics
     */
    @GetMapping("/statistics")
    fun getQueryStatistics(): ResponseEntity<Map<String, Any>> {
        logger.debug("Retrieving query statistics")

        return try {
            val statistics = pqlQueryService.getQueryStatistics()
            ResponseEntity.ok(statistics)
        } catch (e: Exception) {
            logger.error("Error retrieving query statistics", e)
            ResponseEntity.internalServerError().build()
        }
    }

    /**
     * Get supported PQL features
     * GET /api/query/features
     */
    @GetMapping("/features")
    fun getSupportedFeatures(): ResponseEntity<PQLFeaturesResponse> {
        logger.debug("Retrieving supported PQL features")

        val features =
            PQLFeaturesResponse(
                supportedClauses = listOf("SELECT", "FROM", "WHERE"),
                supportedOperators = listOf("=", "!=", "<>", "<", ">", "<=", ">=", "LIKE"),
                supportedEntities = listOf("log", "trace", "event"),
                supportedFields =
                mapOf(
                    "log" to listOf("id", "logId", "name", "createdAt", "updatedAt", "attributes"),
                    "trace" to listOf("id", "traceId", "caseId", "createdAt", "attributes"),
                    "event" to
                        listOf(
                            "id",
                            "eventId",
                            "activity",
                            "timestamp",
                            "resource",
                            "lifecycle",
                            "cost",
                            "createdAt",
                            "attributes",
                        ),
                ),
                limitations =
                listOf(
                    "Complex joins not yet supported",
                    "Subqueries not yet supported",
                ),
                examples =
                listOf(
                    "SELECT * FROM log",
                    "SELECT * FROM trace WHERE caseId = 'case-123'",
                    "SELECT activity, timestamp FROM event WHERE activity = 'Task A'",
                    "SELECT * FROM event WHERE resource LIKE 'John' AND timestamp > '2023-01-01'",
                    "SELECT t:caseId, count(e:id) GROUP BY t:caseId",
                    "SELECT avg(e:cost:total) WHERE e:activity = 'Surgery'",
                ),
            )

        return ResponseEntity.ok(features)
    }

    /**
     * Verify PQL query against ProcessM
     * POST /api/query/verify
     */
    @PostMapping("/verify")
    fun verifyQuery(
        @RequestBody request: PQLVerificationRequest,
    ): ResponseEntity<PQLVerificationResponse> {
        logger.info("=== Verifying PQL Query ===")
        logger.info("PQL: ${request.query}")
        logger.info("LogName: ${request.logName}")

        return try {
            // 1. Execute Local
            val localResult = pqlQueryService.executePQLQuery(request.query, request.logId)
            
            // 2. Execute Remote
            val remoteResult = remoteProcessMService.executeQuery(request.logName, request.query)

            // 3. Compare
            val match = if (localResult.success && remoteResult.success) {
                localResult.resultCount == remoteResult.resultCount
            } else {
                false
            }

            val details = StringBuilder()
            details.append("Local: ${if (localResult.success) "Success (${localResult.resultCount} rows)" else "Fail: ${localResult.error}"}\n")
            details.append("Remote: ${if (remoteResult.success) "Success (${remoteResult.resultCount} rows)" else "Fail: ${remoteResult.message}"}\n")
            
            if (match) {
                details.append("Result: MATCH")
            } else {
                details.append("Result: MISMATCH")
            }

            val response = PQLVerificationResponse(
                match = match,
                localSuccess = localResult.success,
                remoteSuccess = remoteResult.success,
                localCount = localResult.resultCount,
                remoteCount = remoteResult.resultCount,
                details = details.toString()
            )

            ResponseEntity.ok(response)
        } catch (e: Exception) {
            logger.error("Error verifying PQL query", e)
            ResponseEntity.internalServerError().body(
                PQLVerificationResponse(
                    match = false,
                    localSuccess = false,
                    remoteSuccess = false,
                    details = "Internal error: ${e.message}"
                )
            )
        }
    }

    /**
     * Global exception handler for this controller
     */
    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ErrorResponse> {
        logger.error("Unhandled exception in PQLQueryController", e)

        val errorResponse =
            ErrorResponse(
                error = e.javaClass.simpleName,
                message = e.message ?: "An unexpected error occurred",
            )

        return ResponseEntity.internalServerError().body(errorResponse)
    }
}

/**
 * DTO for PQL query execution request
 */
data class PQLQueryRequest(
    val query: String,
    val logId: String? = null,
    val timeout: Long? = null,
    val maxResults: Int? = null,
)

/**
 * DTO for PQL query execution response
 */
data class PQLQueryResponse(
    val success: Boolean,
    val query: String,
    val cypherQuery: String? = null,
    val results: List<Map<String, Any?>> = emptyList(),
    val resultCount: Int = 0,
    val executionTimeMs: Long = 0,
    val error: String? = null,
    val timestamp: LocalDateTime = LocalDateTime.now(),
)

/**
 * DTO for PQL verification request
 */
data class PQLVerificationRequest(
    val query: String,
    val logId: String? = null,
    val logName: String
)

/**
 * DTO for PQL verification response
 */
data class PQLVerificationResponse(
    val match: Boolean,
    val localSuccess: Boolean,
    val remoteSuccess: Boolean,
    val localCount: Int = 0,
    val remoteCount: Int = 0,
    val details: String
)

/**
 * DTO for PQL query validation request
 */
data class PQLValidationRequest(
    val query: String,
)

/**
 * DTO for PQL query validation response
 */
data class PQLValidationResponse(
    val valid: Boolean,
    val query: String,
    val cypherQuery: String? = null,
    val message: String,
    val error: String? = null,
    val timestamp: LocalDateTime = LocalDateTime.now(),
)

/**
 * DTO for supported PQL features
 */
data class PQLFeaturesResponse(
    val supportedClauses: List<String>,
    val supportedOperators: List<String>,
    val supportedEntities: List<String>,
    val supportedFields: Map<String, List<String>>,
    val limitations: List<String>,
    val examples: List<String>,
)
