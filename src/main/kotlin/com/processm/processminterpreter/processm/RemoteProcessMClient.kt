package com.processm.processminterpreter.processm

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.processm.processminterpreter.processm.ProcessMUploadResponse
import com.processm.processminterpreter.processm.RemoteProcessMDataStore
import com.processm.processminterpreter.processm.RemoteProcessMGateway
import com.processm.processminterpreter.processm.RemoteQueryExecutionResult
import com.processm.processminterpreter.processm.json.ProcessMXesJsonParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestOperations
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.nio.charset.StandardCharsets

/**
 * Single adapter for the remote ProcessM system.
 *
 * It owns:
 * - verification query execution,
 * - remote result fetching,
 * - log upload.
 */
@Component
class RemoteProcessMClient(
    private val transport: HttpTransport,
    private val rest: RestOperations,
    @param:Value("\${processm.api.url:http://localhost:80/api}")
    private val baseUrl: String,
    @param:Value("\${processm.login:brains@acme.example.com}")
    private val loginEmail: String,
    @param:Value("\${processm.password:P@ssw0rd}")
    private val password: String,
) : RemoteProcessMGateway {

    private val log = LoggerFactory.getLogger(RemoteProcessMClient::class.java)
    private val json = ObjectMapper()

    override fun executeQuery(
        query: String,
        remoteDataStoreId: String?,
        includeTraces: Boolean,
        includeEvents: Boolean,
    ): RemoteQueryExecutionResult =
        try {
            executeRemoteQuery(query, remoteDataStoreId, includeTraces, includeEvents).toVerificationResult()
        } catch (e: Exception) {
            log.warn("Remote ProcessM verification query failed", e)
            RemoteQueryExecutionResult(success = false, message = "Exception: ${e.message}")
        }

    override fun uploadLog(
        bytes: ByteArray,
        originalFilename: String?,
        logName: String,
    ): ProcessMUploadResponse =
        try {
            val token = login()
            val storeName = if (logName.contains(".")) logName.substringBefore(".") else logName
            val storeId = createDataStore(token, storeName)
                ?: return ProcessMUploadResponse(false, "Error creating data store")

            val headers = HttpHeaders().apply {
                setBearerAuth(token)
                contentType = MediaType.MULTIPART_FORM_DATA
            }
            val body = LinkedMultiValueMap<String, Any>().apply {
                add(
                    "file",
                    object : ByteArrayResource(bytes) {
                        override fun getFilename(): String = originalFilename?.takeUnless { it.isBlank() } ?: DEFAULT_UPLOAD_FILENAME
                    },
                )
            }

            val response = rest.postForEntity(
                "$baseUrl/data-stores/$storeId/logs",
                HttpEntity(body, headers),
                String::class.java,
            )

            if (response.statusCode.is2xxSuccessful) {
                ProcessMUploadResponse(true, "Success: Log uploaded to DataStore $storeName ($storeId)")
            } else {
                ProcessMUploadResponse(false, "Error uploading log: ${response.statusCode}")
            }
        } catch (e: Exception) {
            log.warn("Remote ProcessM log upload failed", e)
            ProcessMUploadResponse(false, "Exception: ${e.message}")
        }

    internal fun login(): String {
        val body = json.writeValueAsString(mapOf("login" to loginEmail, "password" to password))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/users/session"))
            .header("Content-Type", JSON_MEDIA_TYPE)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = transport.send(request)
        if (response.statusCode() !in 200..299) {
            throw RuntimeException("Login failed ${response.statusCode()}: ${response.body()}")
        }
        return json.readTree(response.body())["authorizationToken"]?.asText()
            ?: throw RuntimeException("Login response missing authorizationToken")
    }

    override fun listDataStores(): List<RemoteProcessMDataStore> =
        listDataStores(login())

    internal fun listDataStores(token: String): List<RemoteProcessMDataStore> = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/data-stores"))
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON_MEDIA_TYPE)
            .GET()
            .build()
        val response = transport.send(request)
        if (response.statusCode() != 200) emptyList()
        else {
            val stores = json.readTree(response.body())
            if (!stores.isArray) {
                emptyList()
            } else {
                stores.mapNotNull { store ->
                    store["id"]?.asText()?.let { id ->
                        RemoteProcessMDataStore(
                            id = id,
                            name = store["name"]?.asText(),
                        )
                    }
                }
            }
        }
    } catch (e: Exception) {
        log.warn("listDataStores failed", e)
        emptyList()
    }

    internal fun createDataStore(token: String, storeName: String): String? {
        val body = json.writeValueAsString(mapOf("name" to storeName))
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/data-stores"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", JSON_MEDIA_TYPE)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = transport.send(request)
        if (response.statusCode() !in 200..299) {
            return null
        }
        return json.readTree(response.body())["id"]?.asText()
    }

    /**
     * ProcessM requires scoped limits (`limit l:30`). Unscoped `limit 30` is
     * adapted to log-scope.
     */
    internal fun adaptQueryLimit(query: String): String {
        val hasScopedLimit = query.contains(Regex("(?i)\\blimit\\s+[lte]:"))
        if (hasScopedLimit) return query
        val unscoped = Regex("(?i)\\blimit\\s+(\\d+)")
        val match = unscoped.find(query) ?: return query
        val value = match.groupValues[1]
        return query.replace(unscoped, "limit l:$value")
    }

    private fun executeRemoteQuery(
        query: String,
        requestedDataStoreId: String?,
        includeTraces: Boolean,
        includeEvents: Boolean,
    ): RemoteExecutionPayload {
        val token = login()
        val dataStoreId = requestedDataStoreId?.takeIf { it.isNotBlank() }
            ?: listDataStores(token).firstOrNull()?.id
            ?: return RemoteExecutionPayload(success = false, message = "No data stores available")

        val adapted = adaptQueryLimit(query)
        val encoded = URLEncoder.encode(adapted, StandardCharsets.UTF_8)
        val uri = URI.create(
            "$baseUrl/data-stores/$dataStoreId/logs?query=$encoded" +
                "&includeTraces=$includeTraces&includeEvents=$includeEvents",
        )

        val request = HttpRequest.newBuilder()
            .uri(uri)
            .header("Authorization", "Bearer $token")
            .header("Accept", JSON_MEDIA_TYPE)
            .GET()
            .build()

        val response = transport.send(request)
        if (response.statusCode() != 200) {
            return RemoteExecutionPayload(
                success = false,
                message = "ProcessM API error ${response.statusCode()}: ${response.body()}",
                requestUrl = uri.toString(),
                adaptedQuery = adapted,
                remoteDataStoreId = dataStoreId,
            )
        }

        val rawJson = response.body()
        val root = ProcessMXesJsonParser.parse(rawJson)
        val data = if (root.isArray) root else root["data"]
        val results =
            when {
                data == null -> emptyList()
                data.isArray -> json.convertValue(data, object : TypeReference<List<Map<String, Any?>>>() {})
                else -> listOf(json.convertValue(data, object : TypeReference<Map<String, Any?>>() {}))
            }

        return RemoteExecutionPayload(
            success = true,
            message = "Success",
            resultCount = if (data?.isArray == true) data.size() else results.size,
            results = results,
            requestUrl = uri.toString(),
            adaptedQuery = adapted,
            remoteDataStoreId = dataStoreId,
        )
    }

    private companion object {
        const val JSON_MEDIA_TYPE = "application/json"
        const val DEFAULT_UPLOAD_FILENAME = "log.xes"
    }
}

private data class RemoteExecutionPayload(
    val success: Boolean,
    val message: String,
    val resultCount: Int = 0,
    val results: List<Map<String, Any?>> = emptyList(),
    val requestUrl: String? = null,
    val adaptedQuery: String? = null,
    val remoteDataStoreId: String? = null,
) {
    fun toVerificationResult(): RemoteQueryExecutionResult =
        RemoteQueryExecutionResult(
            success = success,
            message = message,
            resultCount = resultCount,
            results = results,
            requestUrl = requestUrl,
            adaptedQuery = adaptedQuery,
            remoteDataStoreId = remoteDataStoreId,
        )
}
