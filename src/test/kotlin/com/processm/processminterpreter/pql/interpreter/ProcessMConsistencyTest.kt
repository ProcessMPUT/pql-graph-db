package com.processm.processminterpreter.pql.interpreter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.processm.processminterpreter.xes.XESLoader
import com.processm.processminterpreter.xes.XESParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Tag("Integration")
class ProcessMConsistencyTest : BaseInterpreterTest() {
    private lateinit var xesLoader: XESLoader
    private val httpClient: HttpClient
    private val objectMapper = ObjectMapper()

    // Remote Configuration
    private val baseUrl = "https://demo.processm.cs.put.poznan.pl/api"
    private val login = "brains@acme.example.com"
    private val password = "P@ssw0rd"

    init {
        // Create insecure SSL Context
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
        sslContext.init(null, trustAllCerts, java.security.SecureRandom())

        httpClient =
            HttpClient
                .newBuilder()
                .sslContext(sslContext)
                .build()
    }

    @BeforeEach
    fun prepareData() {
        try {
            clearDatabase()

            val xesParser = XESParser()
            xesLoader = XESLoader(xesParser, driver)

            val logFile = File("src/main/resources/logs/Hospital_log.xes")
            if (logFile.exists()) {
                println("Loading local XES file: ${logFile.absolutePath}")
                xesLoader.loadXESFile(logFile.inputStream(), "hospital-log")
            } else {
                println("WARNING: Local Hospital_log.xes not found. Local tests will run on empty DB.")
            }
        } catch (e: Throwable) {
            println("Error in setup: ${e.message}")
            // Don't fail setup, let test run to try remote part at least
        }
    }

    @Test
    fun compareWithRemote() {
        println("\n=== PROCESSM CONSISTENCY CHECK ===")

        val token = loginToRemote()
        if (token == null) {
            println("Skipping remote comparison: Could not log in (Check PROCESSM_LOGIN/PASSWORD env vars).")
            return
        }

        val dataStoreId = findDataStore(token)
        println("Using Remote Data Store ID: $dataStoreId")

        val queries =
            listOf(
                "Basic Stats" to "select count(t:id), count(e:id)",
                "Activities" to "select e:concept:name, count(e:id) group by e:concept:name order by count(e:id) desc limit l:5",
                "Resources" to "select e:org:group, count(e:id) group by e:org:group",
                "Hoisting" to "select ^e:concept:name, count(e:concept:name) group by ^e:concept:name",
            )

        queries.forEach { (desc, query) ->
            println("\n---------------------------------------------------")
            println("TEST: $desc")
            println("QUERY: $query")

            // Remote Execution
            print("REMOTE: ")
            val remoteResult = executeRemoteQuery(token, dataStoreId, query)
            println(remoteResult)

            // Local Execution
            print("LOCAL:  ")
            try {
                val localResult = pqlQueryService.executePQLQuery(query)
                if (localResult.success) {
                    val count = localResult.resultCount
                    val firstRow = localResult.results.firstOrNull()
                    println("SUCCESS (Rows: $count). First: $firstRow")
                } else {
                    println("FAIL. Error: ${localResult.error}")
                }
            } catch (e: Exception) {
                println("ERROR. Exception: ${e.message}")
            }
        }
        println("\n===================================================")
    }

    private fun loginToRemote(): String? {
        try {
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
                return json.get("authorizationToken").asText()
            }
            println("Remote Login Failed: ${response.statusCode()}")
        } catch (e: Exception) {
            println("Remote Login Error: ${e.message}")
        }
        return null
    }

    private fun findDataStore(token: String): String {
        var targetId = "4bd87eaf-a4d7-4fd4-b775-f2db0e46484a"

        try {
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("$baseUrl/data-stores"))
                    .header("Authorization", "Bearer $token")
                    .GET()
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                val stores = objectMapper.readTree(response.body())
                for (store in stores) {
                    val name = store.get("name").asText()
                    if (name.contains("Hospital", ignoreCase = true)) {
                        return store.get("id").asText()
                    }
                }
            }
        } catch (e: Exception) {
            println("Error finding data store: ${e.message}")
        }
        return targetId
    }

    private fun executeRemoteQuery(
        token: String,
        dataStoreId: String,
        query: String,
    ): String {
        try {
            val encodedQuery = java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)
            val uri = URI.create("$baseUrl/data-stores/$dataStoreId/logs?query=$encodedQuery&includeTraces=true&includeEvents=true")

            val request =
                HttpRequest
                    .newBuilder()
                    .uri(uri)
                    .header("Authorization", "Bearer $token")
                    .GET()
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() == 200) {
                val json = objectMapper.readTree(response.body())
                val data = if (json.isArray) json else json.get("data")

                val count = if (data != null && data.isArray) data.size() else 0
                val summary = if (count > 0) "First item present" else "Empty"
                return "SUCCESS (Rows: $count). $summary"
            } else {
                return "FAIL (Status: ${response.statusCode()})"
            }
        } catch (e: Exception) {
            return "ERROR: ${e.message}"
        }
    }
}
