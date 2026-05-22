package com.processm.processminterpreter.infrastructure.web.compare

import com.processm.processminterpreter.application.ports.RemoteProcessMGateway
import com.processm.processminterpreter.application.processm.VerifyPqlQueryRequest as VerifyPqlQueryUseCaseRequest
import com.processm.processminterpreter.application.processm.VerifyPqlQueryUseCase
import com.processm.processminterpreter.infrastructure.config.ProcessMConfig
import com.processm.processminterpreter.infrastructure.web.compare.dto.PQLVerificationRequest
import com.processm.processminterpreter.infrastructure.web.compare.dto.PQLVerificationResponse
import com.processm.processminterpreter.infrastructure.web.compare.dto.RemoteProcessMDataStoreResponse
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Developer-facing comparison endpoints used by the local UI and compatibility scripts.
 *
 * These endpoints deliberately share the `/api/query` prefix with the legacy UI,
 * but they are not part of the ProcessM-compatible datastore contract.
 */
@RestController
@RequestMapping("/api/query")
class PqlComparisonController(
    private val verifyQueryUseCase: VerifyPqlQueryUseCase,
    private val remoteProcessM: RemoteProcessMGateway,
    private val responseMapper: PqlComparisonResponseMapper,
    private val processMConfig: ProcessMConfig,
) {
    private val logger = LoggerFactory.getLogger(PqlComparisonController::class.java)

    @PostMapping("/processm/upload", consumes = ["multipart/form-data"])
    fun uploadToProcessM(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("logName") logName: String,
    ): ResponseEntity<Map<String, String>> {
        val result =
            remoteProcessM.uploadLog(
                bytes = file.bytes,
                originalFilename = file.originalFilename,
                logName = logName,
            )
        return if (result.success) {
            ResponseEntity.ok(mapOf("message" to result.message))
        } else {
            ResponseEntity.badRequest().body(mapOf("error" to result.message))
        }
    }

    @GetMapping("/processm/data-stores")
    fun listRemoteProcessMDataStores(): ResponseEntity<List<RemoteProcessMDataStoreResponse>> =
        ResponseEntity.ok(
            remoteProcessM.listDataStores().map {
                RemoteProcessMDataStoreResponse(
                    id = it.id,
                    name = it.name,
                )
            },
        )

    @PostMapping("/verify")
    fun verifyQuery(
        @RequestBody request: PQLVerificationRequest,
        @RequestParam(defaultValue = "full") format: String,
    ): ResponseEntity<PQLVerificationResponse> {
        logger.info("=== Verifying PQL Query ===")
        logger.info("PQL: ${request.query}")
        logger.info("LogName: ${request.logName}")

        return try {
            val result =
                verifyQueryUseCase.verify(
                    VerifyPqlQueryUseCaseRequest(
                        query = request.query,
                        logId = request.logId,
                        dataStoreId = request.dataStoreId,
                        remoteDataStoreId = request.remoteDataStoreId,
                        includeTraces = request.includeTraces,
                        includeEvents = request.includeEvents,
                        defaultLimits = processMConfig.defaultLimits.toHierarchicalLimits(),
                        lightFormat = format.equals("light", ignoreCase = true),
                    ),
                )

            ResponseEntity.ok(responseMapper.toVerificationResponse(result))
        } catch (e: Exception) {
            logger.error("Error verifying PQL query", e)
            ResponseEntity.internalServerError().body(
                responseMapper.toVerificationErrorResponse("Internal error: ${e.message}"),
            )
        }
    }
}
