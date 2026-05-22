package com.processm.processminterpreter.infrastructure.web.query.dto

data class PQLQueryStatisticsResponse(
    val totalQueries: Int,
    val successfulQueries: Int,
    val failedQueries: Int,
    val averageExecutionTime: Double,
)

data class PQLFeaturesResponse(
    val supportedClauses: List<String>,
    val supportedOperators: List<String>,
    val supportedEntities: List<String>,
    val supportedFields: Map<String, List<String>>,
    val limitations: List<String>,
    val examples: List<String>,
)
