package com.processm.processminterpreter.web.query

import com.processm.processminterpreter.pql.ExecutePqlQueryRequest
import com.processm.processminterpreter.pql.ExportQueryAsXesRequest
import com.processm.processminterpreter.pql.PqlQueryService
import com.processm.processminterpreter.pql.ValidatePqlQueryRequest
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.error.PQLCompileError
import com.processm.processminterpreter.web.common.dto.ErrorResponse
import com.processm.processminterpreter.web.query.dto.PQLFeaturesResponse
import com.processm.processminterpreter.web.query.dto.PQLQueryRequest
import com.processm.processminterpreter.web.query.dto.PQLQueryResponse
import com.processm.processminterpreter.web.query.dto.PQLQueryStatisticsResponse
import com.processm.processminterpreter.web.query.dto.PQLValidationRequest
import com.processm.processminterpreter.web.query.dto.PQLValidationResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * REST Controller for PQL query operations.
 *
 * This class stays as the HTTP adapter only. Query execution and verification
 * live in application use cases, while HTTP response shaping stays at the web edge.
 */
@RestController
@RequestMapping("/api/query")
@CrossOrigin(origins = ["*"])
class PQLQueryController(
    private val pqlQueryService: PqlQueryService,
    private val responseMapper: PqlResponseMapper,
) : PqlQueryApi {
    private val logger = LoggerFactory.getLogger(PQLQueryController::class.java)

    override fun executeQuery(
        request: PQLQueryRequest,
        format: String,
    ): ResponseEntity<PQLQueryResponse> {
        logger.debug(
            "Executing PQL query: pql={}, logId={}, dataStoreId={}, format={}",
            request.query,
            request.logId ?: "ALL",
            request.dataStoreId ?: "ALL",
            format,
        )

        return try {
            val result = pqlQueryService.execute(
                ExecutePqlQueryRequest(
                    query = request.query,
                    logId = request.logId,
                    dataStoreId = request.dataStoreId,
                    materializedScopes = if (format.equals("xes", ignoreCase = true)) {
                        setOf(Scope.LOG, Scope.TRACE, Scope.EVENT)
                    } else {
                        emptySet()
                    },
                ),
            )

            logger.trace("Generated Cypher: {}", result.executedQueryDescription)
            logger.debug("PQL query completed with {} rows", result.rowCount)

            ResponseEntity.ok(responseMapper.toQueryResponse(request.query, result, format))
        } catch (e: PQLCompileError) {
            logger.warn("PQL compile error: {}", e.message)
            ResponseEntity.badRequest().body(
                responseMapper.toQueryErrorResponse(
                    request.query,
                    e.message ?: e::class.simpleName ?: "Compile error",
                ),
            )
        } catch (e: Exception) {
            logger.error("Error executing PQL query: {}", request.query, e)
            ResponseEntity.badRequest().body(
                responseMapper.toQueryErrorResponse(request.query, e.message ?: "Unknown error"),
            )
        }
    }

    override fun executeQueryAsXES(
        request: PQLQueryRequest,
        compress: Boolean,
        logName: String,
    ): ResponseEntity<StreamingResponseBody> {
        logger.debug(
            "Executing PQL query as XES: pql={}, logId={}, dataStoreId={}, compress={}, logName={}",
            request.query,
            request.logId ?: "ALL",
            request.dataStoreId ?: "ALL",
            compress,
            logName,
        )

        return try {
            // The query executes here, so compile/execution failures still become a
            // plain error response; only XML serialization is deferred to the
            // response stream, which keeps the full XES file off the heap.
            val prepared = pqlQueryService.prepareXesExport(
                ExportQueryAsXesRequest(
                    query = request.query,
                    logId = request.logId,
                    dataStoreId = request.dataStoreId,
                    compress = compress,
                    logName = logName,
                ),
            )

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val extension = if (compress) "xes.gz" else "xes"
            val filename = "query_result_$timestamp.$extension"

            val contentType =
                if (compress) MediaType.parseMediaType("application/gzip")
                else MediaType.parseMediaType("application/xml")

            val headers =
                HttpHeaders().apply {
                    this.contentType = contentType
                    this.contentDisposition =
                        org.springframework.http.ContentDisposition
                            .attachment()
                            .filename(filename)
                            .build()
                }

            logger.debug("XES export prepared: {} logs, filename={}", prepared.result.logCount, filename)
            ResponseEntity.ok().headers(headers).body(StreamingResponseBody(prepared.write))
        } catch (e: IllegalArgumentException) {
            logger.warn("Rejected XES export: ${e.message}")
            textResponse(ResponseEntity.badRequest(), e.message ?: "Invalid request")
        } catch (e: Exception) {
            logger.error("Error executing PQL query as XES", e)
            textResponse(ResponseEntity.internalServerError(), "Error executing query as XES: ${e.message}")
        }
    }

    private fun textResponse(
        builder: ResponseEntity.BodyBuilder,
        message: String,
    ): ResponseEntity<StreamingResponseBody> =
        builder
            .contentType(MediaType.TEXT_PLAIN)
            .body(StreamingResponseBody { it.write(message.toByteArray()) })

    override fun validateQuery(
        request: PQLValidationRequest,
    ): ResponseEntity<PQLValidationResponse> {
        logger.debug("Validating PQL query: {}", request.query)

        return try {
            val result = pqlQueryService.validate(
                ValidatePqlQueryRequest(
                    query = request.query,
                    logId = request.logId,
                    dataStoreId = request.dataStoreId,
                ),
            )

            ResponseEntity.ok(responseMapper.toValidationResponse(result))
        } catch (e: Exception) {
            logger.error("Unexpected error validating PQL query", e)
            ResponseEntity.internalServerError().body(
                responseMapper.toValidationErrorResponse(request.query, "Internal server error: ${e.message}"),
            )
        }
    }

    override fun getQueryStatistics(): ResponseEntity<PQLQueryStatisticsResponse> {
        logger.debug("Retrieving query statistics")
        return ResponseEntity.ok(
            responseMapper.toStatisticsResponse(pqlQueryService.statistics()),
        )
    }

    override fun getSupportedFeatures(): ResponseEntity<PQLFeaturesResponse> {
        logger.debug("Retrieving supported PQL features")
        return ResponseEntity.ok(
            responseMapper.toFeaturesResponse(pqlQueryService.supportedFeatures()),
        )
    }

    override fun handleException(e: Exception): ResponseEntity<ErrorResponse> {
        logger.error("Unhandled exception in PQLQueryController", e)

        val errorResponse =
            ErrorResponse(
                error = e.javaClass.simpleName,
                message = e.message ?: "An unexpected error occurred",
            )

        return ResponseEntity.internalServerError().body(errorResponse)
    }
}
