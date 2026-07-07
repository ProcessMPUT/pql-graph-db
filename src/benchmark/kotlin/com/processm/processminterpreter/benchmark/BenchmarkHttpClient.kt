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
import java.time.Duration
import java.util.UUID
import kotlin.io.path.name
import kotlin.system.measureNanoTime

class BenchmarkHttpClient(
    private val apiBase: String,
    private val login: String,
    private val password: String,
) {
    private val mapper = jacksonObjectMapper()
    private val client: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build()
    private var token: String? = null

    fun authenticateIfSupported() {
        val body = mapper.writeValueAsString(mapOf("login" to login, "password" to password))
        val response = send(
            HttpRequest.newBuilder(uri("/users/session"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
        )
        if (response.statusCode() in 200..299 && response.body().isNotBlank()) {
            val json = mapper.readValue(response.body(), object : TypeReference<Map<String, Any?>>() {})
            token = json["authorizationToken"]?.toString()
        }
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
    ): TimedUploadResult {
        val before = listLogs(dataStoreId).size
        var statusCode = 0
        val seconds = measureSeconds {
            val response = uploadLog(dataStoreId, file)
            statusCode = response.statusCode()
            require(statusCode in 200..299) {
                "Upload failed ($statusCode): ${response.body()}"
            }
            waitForLogCount(dataStoreId, before + 1, timeout)
        }
        return TimedUploadResult(seconds = seconds, statusCode = statusCode, logCount = listLogs(dataStoreId).size)
    }

    fun executeQuery(
        dataStoreId: String,
        query: String,
    ): TimedQueryResult {
        var statusCode = 0
        var bytes = 0L
        var body = ""
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
            require(statusCode in 200..299) {
                "Query failed ($statusCode): ${body.take(500)}"
            }
        }
        // Response-count parsing happens outside the timed interval so Q4 parity
        // bookkeeping does not inflate the measured end-to-end latency.
        val counts = XesJsonCounting.count(body)
        return TimedQueryResult(seconds = seconds, statusCode = statusCode, responseBytes = bytes, counts = counts)
    }

    fun exportXesZip(dataStoreId: String): ByteArray {
        val response = sendAuthorizedBytes(
            HttpRequest.newBuilder(uri("/data-stores/$dataStoreId/logs"))
                .timeout(Duration.ofMinutes(10))
                .header("Accept", "application/zip")
                .GET()
                .build(),
        )
        require(response.statusCode() in 200..299) {
            "XES export failed (${response.statusCode()}): ${String(response.body(), StandardCharsets.UTF_8).take(500)}"
        }
        return response.body()
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
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            val count = listLogs(dataStoreId).size
            if (count >= expectedCount) return
            Thread.sleep(1_000)
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
)

data class TimedQueryResult(
    val seconds: Double,
    val statusCode: Int,
    val responseBytes: Long,
    val counts: XesJsonCounts = XesJsonCounts.EMPTY,
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
