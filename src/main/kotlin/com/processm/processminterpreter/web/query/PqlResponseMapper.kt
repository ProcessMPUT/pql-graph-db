package com.processm.processminterpreter.web.query

import com.processm.processminterpreter.processm.json.QueryJsonProjection
import com.processm.processminterpreter.processm.json.ProcessMXesJsonFormatter
import com.processm.processminterpreter.pql.PqlQueryStatisticsSummary
import com.processm.processminterpreter.pql.QueryResult
import com.processm.processminterpreter.pql.SupportedPqlFeatures
import com.processm.processminterpreter.pql.ValidationResult
import com.processm.processminterpreter.web.query.dto.PQLFeaturesResponse
import com.processm.processminterpreter.web.query.dto.PQLQueryResponse
import com.processm.processminterpreter.web.query.dto.PQLQueryStatisticsResponse
import com.processm.processminterpreter.web.query.dto.PQLValidationResponse
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class PqlResponseMapper(
    private val processMXesJsonFormatter: ProcessMXesJsonFormatter,
) {
    fun toQueryResponse(
        query: String,
        result: QueryResult,
        format: String,
    ): PQLQueryResponse =
        PQLQueryResponse(
            success = true,
            query = query,
            cypherQuery = result.executedQueryDescription,
            results = formatResults(result, format),
            resultCount = result.rowCount,
            executionTimeMs = 0,
            timestamp = LocalDateTime.now(),
        )

    fun toQueryErrorResponse(query: String, message: String): PQLQueryResponse =
        PQLQueryResponse(
            success = false,
            query = query,
            error = message,
            results = emptyList(),
            resultCount = 0,
            executionTimeMs = 0,
            timestamp = LocalDateTime.now(),
        )

    fun toValidationResponse(result: ValidationResult): PQLValidationResponse =
        PQLValidationResponse(
            valid = result.valid,
            query = result.query,
            cypherQuery = null,
            message = if (result.valid) "Query is valid" else "Query has errors",
            error = result.errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
            timestamp = LocalDateTime.now(),
        )

    fun toValidationErrorResponse(query: String, message: String): PQLValidationResponse =
        PQLValidationResponse(
            valid = false,
            query = query,
            message = "Validation failed",
            error = message,
            timestamp = LocalDateTime.now(),
        )

    fun toStatisticsResponse(result: PqlQueryStatisticsSummary): PQLQueryStatisticsResponse =
        PQLQueryStatisticsResponse(
            totalQueries = result.totalQueries,
            successfulQueries = result.successfulQueries,
            failedQueries = result.failedQueries,
            averageExecutionTime = result.averageExecutionTime,
        )

    fun toFeaturesResponse(result: SupportedPqlFeatures): PQLFeaturesResponse =
        PQLFeaturesResponse(
            supportedClauses = result.supportedClauses,
            supportedOperators = result.supportedOperators,
            supportedEntities = result.supportedEntities,
            supportedFields = result.supportedFields,
            limitations = result.limitations,
            examples = result.examples,
        )

    private fun formatResults(
        result: QueryResult,
        format: String,
    ): List<Map<String, Any?>> =
        if (format.equals("xes", ignoreCase = true)) {
            processMXesJsonFormatter.formatAsXesJson(
                QueryJsonProjection(
                    logs = result.logs,
                    hasExplicitSelect = result.hasExplicitSelect,
                    selectAllScopes = result.selectAllScopes,
                    projectedLogAttributes = result.projectedLogAttributes,
                    projectedTraceStandardAttributes = result.projectedTraceStandardAttributes,
                ),
            )
        } else {
            result.rows
        }
}
