package com.processm.processminterpreter.web.query

import com.processm.processminterpreter.web.common.dto.ErrorResponse
import com.processm.processminterpreter.web.query.dto.PQLFeaturesResponse
import com.processm.processminterpreter.web.query.dto.PQLQueryRequest
import com.processm.processminterpreter.web.query.dto.PQLQueryResponse
import com.processm.processminterpreter.web.query.dto.PQLQueryStatisticsResponse
import com.processm.processminterpreter.web.query.dto.PQLValidationRequest
import com.processm.processminterpreter.web.query.dto.PQLValidationResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

/**
 * Readable HTTP contract for query-related endpoints.
 *
 * The controller implements this interface so the public surface is easy to scan
 * in one place while execution details stay in the controller implementation.
 */
interface PqlQueryApi {
    @PostMapping("/execute")
    fun executeQuery(
        @RequestBody request: PQLQueryRequest,
        @RequestParam(defaultValue = "json") format: String,
    ): ResponseEntity<PQLQueryResponse>

    @PostMapping("/execute-xes")
    fun executeQueryAsXES(
        @RequestBody request: PQLQueryRequest,
        @RequestParam(defaultValue = "false") compress: Boolean,
        @RequestParam(defaultValue = "Query Result Log") logName: String,
    ): ResponseEntity<StreamingResponseBody>

    @PostMapping("/validate")
    fun validateQuery(
        @RequestBody request: PQLValidationRequest,
    ): ResponseEntity<PQLValidationResponse>

    @GetMapping("/statistics")
    fun getQueryStatistics(): ResponseEntity<PQLQueryStatisticsResponse>

    @GetMapping("/features")
    fun getSupportedFeatures(): ResponseEntity<PQLFeaturesResponse>

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ErrorResponse>
}
