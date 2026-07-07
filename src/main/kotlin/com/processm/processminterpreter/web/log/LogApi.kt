package com.processm.processminterpreter.web.log

import com.processm.processminterpreter.web.log.dto.CreateLogRequest
import com.processm.processminterpreter.web.log.dto.DeleteResponse
import com.processm.processminterpreter.web.log.dto.LogResponse
import com.processm.processminterpreter.web.log.dto.LogSearchRequest
import com.processm.processminterpreter.web.log.dto.LogWithStatisticsResponse
import com.processm.processminterpreter.web.log.dto.SampleLogResourceResponse
import com.processm.processminterpreter.web.log.dto.UpdateLogRequest
import com.processm.processminterpreter.web.log.dto.XESUploadResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile

/**
 * Readable HTTP contract for log-management endpoints.
 *
 * The controller implements this interface so the endpoint surface stays easy to
 * scan in one place, while the controller class focuses on orchestration and
 * response handling.
 */
interface LogApi {
    @PostMapping("/upload")
    fun uploadXESFile(
        @RequestParam("file") file: MultipartFile,
        @RequestParam("logId", required = false) logId: String?,
    ): ResponseEntity<XESUploadResponse>

    @PostMapping("/load-sample")
    fun loadSampleXES(
        @RequestParam("resourcePath", defaultValue = "logs/sample_process.xes") resourcePath: String,
        @RequestParam("logId", required = false) logId: String?,
        @RequestParam("dataStoreId", required = false) dataStoreId: String?,
    ): ResponseEntity<XESUploadResponse>

    @GetMapping("/samples")
    fun listSampleXES(): ResponseEntity<List<SampleLogResourceResponse>>

    @PostMapping
    fun createLog(
        @RequestBody request: CreateLogRequest,
    ): ResponseEntity<LogResponse>

    @GetMapping("/{logId}")
    fun getLog(
        @PathVariable logId: String,
    ): ResponseEntity<LogResponse>

    @GetMapping("/{logId}/statistics")
    fun getLogWithStatistics(
        @PathVariable logId: String,
    ): ResponseEntity<LogWithStatisticsResponse>

    @GetMapping
    fun getAllLogs(
        @RequestParam(defaultValue = "false") includeStatistics: Boolean,
    ): ResponseEntity<List<*>>

    @PostMapping("/search")
    fun searchLogs(
        @RequestBody request: LogSearchRequest,
    ): ResponseEntity<List<LogResponse>>

    @PutMapping("/{logId}")
    fun updateLog(
        @PathVariable logId: String,
        @RequestBody request: UpdateLogRequest,
    ): ResponseEntity<LogResponse>

    @DeleteMapping("/{logId}")
    fun deleteLog(
        @PathVariable logId: String,
        @RequestParam(defaultValue = "true") deleteAllData: Boolean,
    ): ResponseEntity<DeleteResponse>

    @RequestMapping(value = ["/{logId}"], method = [RequestMethod.HEAD])
    fun logExists(
        @PathVariable logId: String,
    ): ResponseEntity<Unit>

    @GetMapping("/generate-id")
    fun generateLogId(): ResponseEntity<Map<String, String>>
}
