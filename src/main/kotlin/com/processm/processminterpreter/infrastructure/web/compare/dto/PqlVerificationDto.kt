package com.processm.processminterpreter.infrastructure.web.compare.dto

data class PQLVerificationRequest(
    val query: String,
    val logId: String? = null,
    val dataStoreId: String? = null,
    val remoteDataStoreId: String? = null,
    val logName: String? = null,
    val includeTraces: Boolean = false,
    val includeEvents: Boolean = false,
)

data class PQLVerificationResponse(
    val match: Boolean,
    val localSuccess: Boolean,
    val remoteSuccess: Boolean,
    val localCount: Int = 0,
    val remoteCount: Int = 0,
    val localResults: List<Map<String, Any?>> = emptyList(),
    val remoteResults: List<Map<String, Any?>> = emptyList(),
    val remoteRequestUrl: String? = null,
    val remoteAdaptedQuery: String? = null,
    val remoteDataStoreId: String? = null,
    val details: String,
)

data class RemoteProcessMDataStoreResponse(
    val id: String,
    val name: String?,
)
