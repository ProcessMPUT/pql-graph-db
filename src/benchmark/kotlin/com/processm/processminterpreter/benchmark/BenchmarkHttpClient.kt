package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlin.io.path.name
import kotlin.system.measureNanoTime

class BenchmarkHttpClient @JvmOverloads constructor(
    private val apiBase: String,
    private val login: String,
    private val password: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mapper = jacksonObjectMapper()
    private val client: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build()
    private var token: String? = null
    private var sessionExpiresAt: Instant? = null
    private var authenticationUnsupported = false

    @JvmOverloads
    fun authenticateIfSupported(allowUnsupported: Boolean = false) {
        val response = requestSession()
        if (allowUnsupported && response.statusCode() in setOf(404, 405)) {
            authenticationUnsupported = true
            token = null
            sessionExpiresAt = null
            return
        }
        acceptSession(response, Duration.ZERO)
    }

    /** Called between operations by the collector, never from a measured request or a retry. */
    fun ensureSessionValid(minimumValidity: Duration): Boolean {
        require(!minimumValidity.isNegative) { "Session validity reserve cannot be negative" }
        if (authenticationUnsupported) return false
        val expiration = checkNotNull(sessionExpiresAt) { "Authenticate before checking session validity" }
        if (expiration.isAfter(clock.instant().plus(minimumValidity))) return false
        // Credentials work even after a long pause exceeding the expired token's renewal grace period.
        acceptSession(requestSession(), minimumValidity)
        return true
    }

    private fun requestSession(): HttpResponse<String> {
        val body = mapper.writeValueAsString(mapOf("login" to login, "password" to password))
        return send(
            HttpRequest.newBuilder(uri("/users/session"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
        )
    }

    private fun acceptSession(response: HttpResponse<String>, minimumValidity: Duration) {
        check(response.statusCode() in 200..299) { "Session authentication failed (HTTP ${response.statusCode()})" }
        // JWT payload decoding is only a refresh schedule, not local signature verification.
        // Never include the response, token or claims in an exception or evidence file.
        val (newToken, expiration) = try {
            val tokenNode = mapper.readTree(response.body())?.get("authorizationToken")
            require(tokenNode != null && tokenNode.isTextual)
            val value = tokenNode.asText()
            val parts = value.split('.')
            require(parts.size == 3 && parts.all(String::isNotBlank))
            val claim = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]))?.get("exp")
            require(claim != null && claim.isIntegralNumber && claim.canConvertToLong())
            value to Instant.ofEpochSecond(claim.asLong())
        } catch (_: Exception) {
            error("Session response contains no valid JWT expiration")
        }
        check(expiration.isAfter(clock.instant().plus(minimumValidity))) {
            "Session token lifetime is shorter than the required operation reserve"
        }
        token = newToken
        sessionExpiresAt = expiration
        authenticationUnsupported = false
    }

    fun createDataStore(name: String): String {
        val body = mapper.writeValueAsString(mapOf("name" to name))
        val response = sendAuthorized(
            HttpRequest.newBuilder(uri("/data-stores"))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
        )
        require(response.statusCode() in 200..299) {
            "Create datastore failed (${response.statusCode()}): ${response.body()}"
        }
        val json = mapper.readValue(response.body(), object : TypeReference<Map<String, Any?>>() {})
        return requireNotNull(json["id"]?.toString()) { "Create datastore response has no id: ${response.body()}" }
    }

    fun listDataStores(): List<RemoteDataStore> {
        val response = sendAuthorized(
            HttpRequest.newBuilder(uri("/data-stores"))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .GET()
                .build(),
        )
        require(response.statusCode() in 200..299) {
            "List datastores failed (${response.statusCode()}): ${response.body()}"
        }
        return mapper
            .readValue(response.body(), object : TypeReference<List<Map<String, Any?>>>() {})
            .mapNotNull { item ->
                val id = item["id"]?.toString()
                val name = item["name"]?.toString()
                if (id == null || name == null) null else RemoteDataStore(id = id, name = name)
            }
    }

    fun deleteDataStore(dataStoreId: String): DeleteDataStoreResult {
        val response = sendAuthorized(
            HttpRequest.newBuilder(uri("/data-stores/$dataStoreId"))
                .timeout(Duration.ofMinutes(2))
                .header("Accept", "application/json")
                .DELETE()
                .build(),
        )
        val status = when (response.statusCode()) {
            in 200..299 -> "DELETED"
            404 -> "NOT_FOUND"
            else -> "ERROR"
        }
        return DeleteDataStoreResult(status = status, statusCode = response.statusCode(), body = response.body())
    }

    fun uploadLogAndWait(
        dataStoreId: String,
        file: Path,
        timeout: Duration = Duration.ofMinutes(15),
        observe: (Map<String, Any?>) -> Unit = {},
    ): TimedUploadResult {
        val before = listLogs(dataStoreId).size
        var statusCode = 0
        val startedAt = Instant.now().toString()
        val startedNanos = System.nanoTime()
        val seconds = measureSeconds {
            val response = uploadLog(dataStoreId, file)
            statusCode = response.statusCode()
            observe(mapOf("stage" to "upload-response", "atNanos" to System.nanoTime(), "httpStatus" to statusCode))
            require(statusCode in 200..299) {
                "Upload failed ($statusCode): ${response.body()}"
            }
            waitForLogCount(dataStoreId, before + 1, timeout, observe)
        }
        val finishedNanos = System.nanoTime()
        return TimedUploadResult(seconds, statusCode, listLogs(dataStoreId).size, startedAt, startedNanos, finishedNanos)
    }

    fun executeQuery(
        dataStoreId: String,
        query: String,
    ): TimedQueryResult {
        var statusCode = 0
        var bytes = 0L
        var body = ""
        val startedAt = Instant.now().toString()
        val startedNanos = System.nanoTime()
        val seconds = measureSeconds {
            val encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8)
            val response = sendAuthorized(
                HttpRequest.newBuilder(uri("/data-stores/$dataStoreId/logs?query=$encodedQuery"))
                    .timeout(Duration.ofMinutes(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build(),
            )
            statusCode = response.statusCode()
            body = response.body()
            bytes = body.toByteArray(StandardCharsets.UTF_8).size.toLong()
            if (statusCode !in 200..299) throw BenchmarkRequestException(statusCode, body,
                startedAt, startedNanos, System.nanoTime())
        }
        val finishedNanos = System.nanoTime()
        // Response-count parsing happens outside the timed interval so Q4 parity
        // bookkeeping does not inflate the measured end-to-end latency.
        val counts = XesJsonCounting.count(body)
        return TimedQueryResult(
            seconds = seconds,
            statusCode = statusCode,
            responseBytes = bytes,
            counts = counts,
            body = body,
            startedAt = startedAt,
            startedNanos = startedNanos,
            finishedNanos = finishedNanos,
        )
    }

    fun exportQueryAsXes(dataStoreId: String): ByteArray {
        val body = mapper.writeValueAsString(mapOf("query" to "", "dataStoreId" to dataStoreId))
        val response = sendAuthorizedBytes(
            HttpRequest.newBuilder(uri("/query/execute-xes?compress=false"))
                .timeout(Duration.ofMinutes(10))
                .header("Accept", "application/xml")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
        )
        require(response.statusCode() in 200..299) {
            "XES export failed (${response.statusCode()}): ${String(response.body(), StandardCharsets.UTF_8).take(500)}"
        }
        return response.body()
    }

    fun listLogs(dataStoreId: String): List<Map<String, Any?>> {
        val response = sendAuthorized(
            HttpRequest.newBuilder(uri("/data-stores/$dataStoreId/logs"))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .GET()
                .build(),
        )
        require(response.statusCode() in 200..299) {
            "List logs failed (${response.statusCode()}): ${response.body()}"
        }
        return mapper.readValue(response.body(), object : TypeReference<List<Map<String, Any?>>>() {})
    }

    private fun waitForLogCount(
        dataStoreId: String,
        expectedCount: Int,
        timeout: Duration,
        observe: (Map<String, Any?>) -> Unit,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            val started = System.nanoTime()
            val count = listLogs(dataStoreId).size
            observe(mapOf("stage" to "readiness-poll", "startedNanos" to started,
                "finishedNanos" to System.nanoTime(), "logCount" to count, "expectedCount" to expectedCount))
            if (count >= expectedCount) return
            // Import readiness is asynchronous in REFERENCE. A one-second poll
            // interval dominated small imports and quantized their measured time;
            // each unsuccessful poll is followed by a 100 ms sleep plus the next request latency.
            Thread.sleep(100)
        }
        error("Timed out waiting for $expectedCount log(s) in datastore $dataStoreId")
    }

    private fun uploadLog(
        dataStoreId: String,
        file: Path,
    ): HttpResponse<String> {
        val boundary = "----ProcessMBenchmark${UUID.randomUUID()}"
        val body = multipartBody(boundary, file)
        return sendAuthorized(
            HttpRequest.newBuilder(uri("/data-stores/$dataStoreId/logs"))
                .timeout(Duration.ofMinutes(15))
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=$boundary")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build(),
        )
    }

    private fun multipartBody(
        boundary: String,
        file: Path,
    ): ByteArray {
        val contentType = Files.probeContentType(file) ?: "application/octet-stream"
        val header =
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n" +
                "Content-Type: $contentType\r\n\r\n"
        val footer = "\r\n--$boundary--\r\n"
        return header.toByteArray(StandardCharsets.UTF_8) +
            Files.readAllBytes(file) +
            footer.toByteArray(StandardCharsets.UTF_8)
    }

    private fun sendAuthorized(request: HttpRequest): HttpResponse<String> =
        send(withAuthorization(request))

    private fun sendAuthorizedBytes(request: HttpRequest): HttpResponse<ByteArray> =
        client.send(withAuthorization(request), HttpResponse.BodyHandlers.ofByteArray())

    private fun send(request: HttpRequest): HttpResponse<String> =
        client.send(request, HttpResponse.BodyHandlers.ofString())

    private fun withAuthorization(request: HttpRequest): HttpRequest {
        val currentToken = token ?: return request
        val builder = HttpRequest.newBuilder(request.uri())
            .timeout(request.timeout().orElse(Duration.ofMinutes(10)))
            .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
        request.headers().map().forEach { (name, values) ->
            values.forEach { value -> builder.header(name, value) }
        }
        return builder.header("Authorization", "Bearer $currentToken").build()
    }

    private fun uri(path: String): URI = URI.create("$apiBase$path")
}

data class TimedUploadResult(
    val seconds: Double,
    val statusCode: Int,
    val logCount: Int,
    val startedAt: String = "",
    val startedNanos: Long? = null,
    val finishedNanos: Long? = null,
)

data class TimedQueryResult(
    val seconds: Double,
    val statusCode: Int,
    val responseBytes: Long,
    val counts: XesJsonCounts = XesJsonCounts.EMPTY,
    /** Raw response retained only long enough to perform untimed semantic parity. */
    val body: String = "",
    val startedAt: String = "",
    val startedNanos: Long? = null,
    val finishedNanos: Long? = null,
)

data class RemoteDataStore(
    val id: String,
    val name: String,
)

data class DeleteDataStoreResult(
    val status: String,
    val statusCode: Int,
    val body: String,
)

fun measureSeconds(block: () -> Unit): Double =
    measureNanoTime(block) / 1_000_000_000.0
