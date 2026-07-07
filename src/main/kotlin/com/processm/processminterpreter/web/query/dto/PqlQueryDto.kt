package com.processm.processminterpreter.web.query.dto

import java.time.LocalDateTime

data class PQLQueryRequest(
    val query: String,
    val logId: String? = null,
    val dataStoreId: String? = null,
    val timeout: Long? = null,
    val maxResults: Int? = null,
)

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
