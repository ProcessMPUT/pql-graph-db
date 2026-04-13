package com.processm.processminterpreter.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import org.springframework.web.multipart.MultipartFile
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Service
class RemoteProcessMService(
    @Value("\${processm.api.url:http://localhost:80/api}")
    private val baseUrl: String,
    @Value("\${processm.login:brains@acme.example.com}")
    private val login: String,
    @Value("\${processm.password:P@ssw0rd}")
    private val password: String,
) {
    private val httpClient: HttpClient
    private val objectMapper = ObjectMapper()

    init {
        // Create insecure SSL Context (simulating test setup for compatibility)
        val trustAllCerts =
            arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(
                        certs: Array<X509Certificate>,
                        authType: String,
                    ) {}

                    override fun checkServerTrusted(
                        certs: Array<X509Certificate>,
                        authType: String,
                    ) {}

                    override fun getAcceptedIssuers(): Array<X509Certificate>? = null
                },
            )

        val sslContext = SSLContext.getInstance("SSL")
        sslContext.init(null, trustAllCerts, SecureRandom())

        httpClient =
            HttpClient
                .newBuilder()
                .sslContext(sslContext)
                .build()
    }

    fun executeQuery(
        logName: String,
        query: String,
        includeTraces: Boolean = false,
        includeEvents: Boolean = false,
    ): RemoteResult {
        try {
            val token = login() ?: return RemoteResult(false, "Could not log in to ProcessM")
            val dataStoreId =
                findDataStore(token)
                    ?: return RemoteResult(false, "Could not find any data stores in ProcessM")

            // Adapt unscoped limits to log-scoped (ProcessM requires l:/t:/e: prefix)
            var adaptedQuery = query
            val hasScopedLimits = adaptedQuery.contains(Regex("(?i)\\blimit\\s+[lte]:"))
            if (!hasScopedLimits) {
                val unscopedLimitRegex = Regex("(?i)\\blimit\\s+(\\d+)")
                val unscopedMatch = unscopedLimitRegex.find(adaptedQuery)
                if (unscopedMatch != null) {
                    val limitValue = unscopedMatch.groupValues[1]
                    adaptedQuery = adaptedQuery.replace(unscopedLimitRegex, "limit l:$limitValue")
                }
            }

            val encodedQuery = URLEncoder.encode(adaptedQuery, StandardCharsets.UTF_8)
            val uri =
                URI.create(
                    "$baseUrl/data-stores/$dataStoreId/logs?query=$encodedQuery&includeTraces=$includeTraces&includeEvents=$includeEvents",
                )

            val request =
                HttpRequest
                    .newBuilder()
                    .uri(uri)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .GET()
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val json = objectMapper.readTree(response.body())
                val data = if (json.isArray) json else json.get("data")

                val count = if (data != null && data.isArray) data.size() else 0
                return RemoteResult(true, "Success", count, data, uri.toString(), adaptedQuery, dataStoreId)
            } else {
                return RemoteResult(
                    false,
                    "Remote API Error: ${response.statusCode()} - ${response.body()}",
                    0,
                    null,
                    uri.toString(),
                    adaptedQuery,
                    dataStoreId,
                )
            }
        } catch (e: Exception) {
            return RemoteResult(false, "Exception: ${e.message}")
        }
    }

    private fun login(): String {
        val authBody = mapOf("login" to login, "password" to password)
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create("$baseUrl/users/session"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(authBody)))
                .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) {
            val json = objectMapper.readTree(response.body())
            if (json.has("authorizationToken")) {
                return json.get("authorizationToken").asText()
            } else {
                throw RuntimeException("Login response missing token: ${response.body()}")
            }
        } else {
            throw RuntimeException("Login failed: ${response.statusCode()} - ${response.body()}")
        }
    }

    fun uploadLog(
        file: MultipartFile,
        logName: String,
    ): String {
        try {
            val token = login()

            // 1. Create Data Store
            // We assume one doesn't exist or we create a new one for this log
            val storeName = if (logName.contains(".")) logName.substringBefore(".") else logName
            val createStoreUri = URI.create("$baseUrl/data-stores")
            val createStoreRequest =
                HttpRequest
                    .newBuilder()
                    .uri(createStoreUri)
                    .header("Authorization", "Bearer $token")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"name": "$storeName"}"""))
                    .build()

            val storeResp = httpClient.send(createStoreRequest, HttpResponse.BodyHandlers.ofString())
            if (storeResp.statusCode() !in 200..299) {
                return "Error creating data store: ${storeResp.statusCode()} - ${storeResp.body()}"
            }
            val storeId = objectMapper.readTree(storeResp.body()).get("id").asText()

            // 2. Upload Log (using RestTemplate for Multipart)
            val restTemplate = RestTemplate()
            val headers = HttpHeaders()
            headers.setBearerAuth(token)
            headers.contentType = MediaType.MULTIPART_FORM_DATA

            val body = LinkedMultiValueMap<String, Any>()
            body.add(
                "file",
                object : ByteArrayResource(file.bytes) {
                    override fun getFilename() = file.originalFilename ?: "log.xes"
                },
            )

            // Assumption: Standard ProcessM/XES import endpoint
            val uploadUrl = "$baseUrl/data-stores/$storeId/import?format=XES"

            val requestEntity = HttpEntity(body, headers)
            val response = restTemplate.postForEntity(uploadUrl, requestEntity, String::class.java)

            if (response.statusCode.is2xxSuccessful) {
                return "Success: Log uploaded to DataStore $storeName ($storeId)"
            } else {
                return "Error uploading log: ${response.statusCode}"
            }
        } catch (e: Exception) {
            return "Exception: ${e.message}"
        }
    }

    private fun findDataStore(token: String): String? {
        try {
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("$baseUrl/data-stores"))
                    .header("Authorization", "Bearer $token")
                    .GET()
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) return null

            val stores = objectMapper.readTree(response.body())
            if (!stores.isArray || stores.size() == 0) return null

            val first = stores.get(0)
            println("Using data store: ${first.get("name")?.asText()} (${first.get("id").asText()})")
            return first.get("id").asText()
        } catch (e: Exception) {
            println("Error fetching data stores: ${e.message}")
        }

        return null
    }
}

data class RemoteResult(
    val success: Boolean,
    val message: String,
    val resultCount: Int = 0,
    val data: JsonNode? = null,
    val requestUrl: String? = null,
    val adaptedQuery: String? = null,
    val remoteLogId: String? = null,
)
