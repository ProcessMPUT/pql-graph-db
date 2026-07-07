package com.processm.processminterpreter.processm

data class RemoteQueryExecutionResult(
    val success: Boolean,
    val message: String,
    val resultCount: Int = 0,
    val results: List<Map<String, Any?>> = emptyList(),
    val requestUrl: String? = null,
    val adaptedQuery: String? = null,
    val remoteDataStoreId: String? = null,
)

data class ProcessMUploadResponse(
    val success: Boolean,
    val message: String,
)

data class RemoteProcessMDataStore(
    val id: String,
    val name: String?,
)

interface RemoteProcessMGateway {
    fun executeQuery(
        query: String,
        remoteDataStoreId: String? = null,
        includeTraces: Boolean = false,
        includeEvents: Boolean = false,
    ): RemoteQueryExecutionResult

    fun uploadLog(
        bytes: ByteArray,
        originalFilename: String?,
        logName: String,
    ): ProcessMUploadResponse

    fun listDataStores(): List<RemoteProcessMDataStore>
}
