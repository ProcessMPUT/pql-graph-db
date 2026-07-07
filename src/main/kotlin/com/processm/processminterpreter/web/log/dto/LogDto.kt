package com.processm.processminterpreter.web.log.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import com.processm.processminterpreter.xes.model.Log as DomainLog
import com.processm.processminterpreter.xes.LogImportResult
import com.processm.processminterpreter.xes.LogStatistics as PortLogStatistics
import java.time.LocalDateTime

// Validation annotations will be added when Jakarta Validation dependency is included
// import jakarta.validation.constraints.NotBlank
// import jakarta.validation.constraints.Size

/**
 * DTO for creating a new log
 */
data class CreateLogRequest(
    val logId: String,
    val name: String,
    @param:JsonProperty("attributes") val attributes: Map<String, Any> = emptyMap(),
)

/**
 * DTO for updating an existing log
 */
data class UpdateLogRequest(
    val name: String? = null,
    @param:JsonProperty("attributes") val attributes: Map<String, Any>? = null,
)

/**
 * DTO for log response
 */
data class LogResponse(
    val logId: String,
    val name: String,
    @param:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") val createdAt: LocalDateTime,
    @param:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") val updatedAt: LocalDateTime,
    @param:JsonProperty("attributes") val attributes: Map<String, Any>,
) {
    companion object {
        fun from(log: DomainLog): LogResponse =
            LogResponse(
                logId = log.id,
                name = log.name,
                createdAt = log.createdAt,
                updatedAt = log.updatedAt,
                attributes = nonNullAttributes(log.customAttributes),
            )
    }
}

/**
 * Internal helper — shared by the two `from(DomainLog…)` mappers below.
 * Filters out null values so we can match the wire shape `Map<String, Any>`.
 */
private fun nonNullAttributes(source: Map<String, Any?>): Map<String, Any> =
    source.mapNotNull { (key, value) ->
        value?.let { key to it }
    }.toMap()

/**
 * DTO for log response with statistics
 */
data class LogWithStatisticsResponse(
    val logId: String,
    val name: String,
    @param:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") val createdAt: LocalDateTime,
    @param:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") val updatedAt: LocalDateTime,
    @param:JsonProperty("attributes") val attributes: Map<String, Any>,
    val statistics: LogStatisticsDto,
) {
    companion object {
        fun from(log: DomainLog, stats: PortLogStatistics): LogWithStatisticsResponse =
            LogWithStatisticsResponse(
                logId = log.id,
                name = log.name,
                createdAt = log.createdAt,
                updatedAt = log.updatedAt,
                attributes = nonNullAttributes(log.customAttributes),
                statistics =
                    LogStatisticsDto(
                        traceCount = stats.traceCount,
                        eventCount = stats.eventCount,
                    ),
            )
    }
}

/**
 * DTO for log statistics
 */
data class LogStatisticsDto(
    val traceCount: Long,
    val eventCount: Long,
)

/**
 * DTO for log search request
 */
data class LogSearchRequest(
    val name: String? = null,
    @param:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") val createdAfter: LocalDateTime? = null,
    @param:JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") val createdBefore: LocalDateTime? = null,
    val attributeKey: String? = null,
    val attributeValue: Any? = null,
)

/**
 * DTO for delete operation response
 */
data class DeleteResponse(
    val success: Boolean,
    val message: String,
    val deletedLogId: String,
    val timestamp: LocalDateTime = LocalDateTime.now(),
)

/**
 * DTO for XES file upload response
 */
data class XESUploadResponse(
    val success: Boolean,
    val logId: String? = null,
    val tracesCount: Int = 0,
    val eventsCount: Int = 0,
    val message: String,
    val error: String? = null,
    val filename: String? = null,
    val timestamp: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        fun from(result: LogImportResult, filename: String?): XESUploadResponse =
            XESUploadResponse(
                success = result.success,
                logId = result.logId,
                tracesCount = result.traceCount,
                eventsCount = result.eventCount,
                message = result.message,
                error = result.error,
                filename = filename,
            )

        fun internalError(message: String, error: String, filename: String?): XESUploadResponse =
            XESUploadResponse(
                success = false,
                message = message,
                error = error,
                filename = filename,
            )
    }
}

data class SampleLogResourceResponse(
    val name: String,
    val resourcePath: String,
)


