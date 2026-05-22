package com.processm.processminterpreter.infrastructure.web.query

import com.processm.processminterpreter.application.query.ExecutePqlQueryRequest
import com.processm.processminterpreter.application.query.ExecutePqlQueryUseCase
import com.processm.processminterpreter.application.query.ExportQueryAsXesRequest
import com.processm.processminterpreter.application.query.ExportQueryAsXesUseCase
import com.processm.processminterpreter.application.query.GetPqlQueryMetadataUseCase
import com.processm.processminterpreter.application.query.ValidatePqlQueryRequest
import com.processm.processminterpreter.application.query.ValidatePqlQueryUseCase
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.error.PQLCompileError
import com.processm.processminterpreter.infrastructure.web.common.dto.ErrorResponse
import com.processm.processminterpreter.infrastructure.web.query.dto.PQLFeaturesResponse
import com.processm.processminterpreter.infrastructure.web.query.dto.PQLQueryRequest
import com.processm.processminterpreter.infrastructure.web.query.dto.PQLQueryResponse
import com.processm.processminterpreter.infrastructure.web.query.dto.PQLQueryStatisticsResponse
import com.processm.processminterpreter.infrastructure.web.query.dto.PQLValidationRequest
import com.processm.processminterpreter.infrastructure.web.query.dto.PQLValidationResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.io.ByteArrayOutputStream
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
    private val executeQueryUseCase: ExecutePqlQueryUseCase,
    private val validateQueryUseCase: ValidatePqlQueryUseCase,
    private val exportQueryUseCase: ExportQueryAsXesUseCase,
    private val queryMetadataUseCase: GetPqlQueryMetadataUseCase,
    private val responseMapper: PqlResponseMapper,
) : PqlQueryApi {
    private val logger = LoggerFactory.getLogger(PQLQueryController::class.java)

    override fun executeQuery(
        request: PQLQueryRequest,
        format: String,
    ): ResponseEntity<PQLQueryResponse> {
        logger.info("=== Executing PQL Query ===")
        logger.info("PQL: ${request.query}")
        logger.info("LogId: ${request.logId ?: "ALL"}")
        logger.info("DataStoreId: ${request.dataStoreId ?: "ALL"}")
        logger.info("Format: $format")

        return try {
            val result = executeQueryUseCase.execute(
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

            logger.info("Generated Cypher: ${result.executedQueryDescription}")
            logger.info("Results count: ${result.rowCount}")

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
    ): ResponseEntity<ByteArray> {
        logger.info("=== Executing PQL Query as XES ===")
        logger.info(
            "PQL: ${request.query}, LogId: ${request.logId ?: "ALL"}, " +
                "DataStoreId: ${request.dataStoreId ?: "ALL"}, compress=$compress, logName=$logName",
        )

        return try {
            val raw = ByteArrayOutputStream()
            exportQueryUseCase.export(
                ExportQueryAsXesRequest(
                    query = request.query,
                    logId = request.logId,
                    dataStoreId = request.dataStoreId,
                    compress = compress,
                    logName = logName,
                ),
                raw,
            )
            val xesBytes = raw.toByteArray()

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
                    this.contentLength = xesBytes.size.toLong()
                }

            logger.info("XES output generated: ${xesBytes.size} bytes, filename: $filename")
            ResponseEntity.ok().headers(headers).body(xesBytes)
        } catch (e: IllegalArgumentException) {
            logger.warn("Rejected XES export: ${e.message}")
            ResponseEntity
                .badRequest()
                .contentType(MediaType.TEXT_PLAIN)
                .body((e.message ?: "Invalid request").toByteArray())
        } catch (e: Exception) {
            logger.error("Error executing PQL query as XES", e)
            val errorMessage = "Error executing query as XES: ${e.message}"
            ResponseEntity
                .internalServerError()
                .contentType(MediaType.TEXT_PLAIN)
                .body(errorMessage.toByteArray())
        }
    }

    override fun validateQuery(
        request: PQLValidationRequest,
    ): ResponseEntity<PQLValidationResponse> {
        logger.debug("Validating PQL query: ${request.query}")

        return try {
            val result = validateQueryUseCase.validate(
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
            responseMapper.toStatisticsResponse(queryMetadataUseCase.statistics()),
        )
    }

    override fun getSupportedFeatures(): ResponseEntity<PQLFeaturesResponse> {
        logger.debug("Retrieving supported PQL features")
        return ResponseEntity.ok(
            responseMapper.toFeaturesResponse(queryMetadataUseCase.supportedFeatures()),
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
