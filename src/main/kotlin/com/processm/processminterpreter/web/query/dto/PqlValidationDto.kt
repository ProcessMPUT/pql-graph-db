package com.processm.processminterpreter.web.query.dto

import java.time.LocalDateTime

/**
 * [logId] and [dataStoreId] are optional query scopes used to resolve
 * classifier metadata during validation.
 */
data class PQLValidationRequest(
    val query: String,
    val logId: String? = null,
    val dataStoreId: String? = null,
)

data class PQLValidationResponse(
    val valid: Boolean,
    val query: String,
    val cypherQuery: String? = null,
    val message: String,
    val error: String? = null,
    val timestamp: LocalDateTime = LocalDateTime.now(),
)
