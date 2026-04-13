package com.processm.processminterpreter.dto

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonProperty
import com.processm.processminterpreter.model.LogNode
import com.processm.processminterpreter.service.LogStatistics
import com.processm.processminterpreter.service.LogWithStatistics
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
    @JsonProperty("attributes") val attributes: Map<String, Any> = emptyMap(),
)

/**
 * DTO for updating an existing log
 */
data class UpdateLogRequest(
    val name: String? = null,
    @JsonProperty("attributes") val attributes: Map<String, Any>? = null,
)

/**
 * DTO for log response
 */
data class LogResponse(
    val logId: String,
    val name: String,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") val createdAt: LocalDateTime,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") val updatedAt: LocalDateTime,
    @JsonProperty("attributes") val attributes: Map<String, Any>,
) {
    companion object {
        fun from(logNode: LogNode): LogResponse =
            LogResponse(
                logId = logNode.logId,
                name = logNode.name,
                createdAt = logNode.createdAt,
                updatedAt = logNode.updatedAt,
                attributes = logNode.attributes,
            )
    }
}

/**
 * DTO for log response with statistics
 */
data class LogWithStatisticsResponse(
    val logId: String,
    val name: String,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") val createdAt: LocalDateTime,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") val updatedAt: LocalDateTime,
    @JsonProperty("attributes") val attributes: Map<String, Any>,
    val statistics: LogStatisticsDto,
) {
    companion object {
        fun from(logWithStats: LogWithStatistics): LogWithStatisticsResponse =
            LogWithStatisticsResponse(
                logId = logWithStats.log.logId,
                name = logWithStats.log.name,
                createdAt = logWithStats.log.createdAt,
                updatedAt = logWithStats.log.updatedAt,
                attributes = logWithStats.log.attributes,
                statistics =
                    LogStatisticsDto(
                        traceCount = logWithStats.traceCount,
                        eventCount = logWithStats.eventCount,
                    ),
            )

        fun from(logStats: LogStatistics): LogWithStatisticsResponse =
            LogWithStatisticsResponse(
                logId = logStats.log.logId,
                name = logStats.log.name,
                createdAt = logStats.log.createdAt,
                updatedAt = logStats.log.updatedAt,
                attributes = logStats.log.attributes,
                statistics =
                    LogStatisticsDto(
                        traceCount = logStats.traceCount,
                        eventCount = logStats.eventCount,
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
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") val createdAfter: LocalDateTime? = null,
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss") val createdBefore: LocalDateTime? = null,
    val attributeKey: String? = null,
    val attributeValue: Any? = null,
)

/**
 * DTO for paginated log response
 */
data class LogPageResponse(
    val logs: List<LogResponse>,
    val totalCount: Long,
    val page: Int,
    val size: Int,
    val totalPages: Int,
)

/**
 * DTO for API error response
 */
data class ErrorResponse(
    val error: String,
    val message: String,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val path: String? = null,
)

/**
 * DTO for successful operation response
 */
data class SuccessResponse(
    val success: Boolean = true,
    val message: String,
    val timestamp: LocalDateTime = LocalDateTime.now(),
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
)
