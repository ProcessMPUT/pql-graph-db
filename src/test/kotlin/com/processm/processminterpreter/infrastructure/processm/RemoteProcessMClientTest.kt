package com.processm.processminterpreter.infrastructure.processm

import com.processm.processminterpreter.application.ports.RemoteProcessMDataStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.springframework.http.HttpEntity
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.util.MultiValueMap
import org.springframework.web.client.RestOperations
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import javax.net.ssl.SSLSession

class RemoteProcessMClientTest {

    private val baseUrl = "http://localhost:80/api"

    private fun restOperations(): RestOperations = Mockito.mock(RestOperations::class.java)

    private fun client(
        transport: HttpTransport,
        rest: RestOperations = restOperations(),
    ): RemoteProcessMClient = RemoteProcessMClient(transport, rest, baseUrl, "u", "p")

    private fun stubResponse(statusCode: Int, body: String): HttpResponse<String> =
        object : HttpResponse<String> {
            override fun statusCode(): Int = statusCode
            override fun body(): String = body
            override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
            override fun sslSession(): Optional<SSLSession> = Optional.empty()
            override fun request(): HttpRequest = HttpRequest.newBuilder(URI.create(baseUrl)).build()
            override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
            override fun uri(): URI = URI.create(baseUrl)
            override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
        }

    /**
     * Route requests by URI path so tests can describe the full flow - login,
     * data-store lookup, remote query - in one place without plumbing request
     * matchers manually.
     */
    private fun transportFor(routes: Map<String, (HttpRequest) -> HttpResponse<String>>): HttpTransport =
        HttpTransport { req ->
            val path = req.uri().path
            val handler = routes[path]
                ?: error("Unexpected request to $path. Known routes: ${routes.keys}")
            handler(req)
        }

    @Test
    fun `executeQuery logs in, picks first data store, and issues the logs GET`() {
        val loginBody = """{"authorizationToken":"token-xyz"}"""
        val dataStoresBody = """[{"id":"ds-1","name":"default"}]"""
        val queryBody = """{"data":[]}"""

        var executedQueryUri: String? = null
        val transport = transportFor(
            mapOf(
                "/api/users/session" to { stubResponse(200, loginBody) },
                "/api/data-stores" to { stubResponse(200, dataStoresBody) },
                "/api/data-stores/ds-1/logs" to { req ->
                    executedQueryUri = req.uri().toString()
                    stubResponse(200, queryBody)
                },
            ),
        )
        val response = client(transport).executeQuery("select e:name limit 5", includeTraces = true, includeEvents = true)

        assertTrue(response.success, "expected success, got message=${response.message}")
        assertEquals(0, response.resultCount)
        assertTrue(
            executedQueryUri!!.contains("limit") || executedQueryUri!!.contains("limit%20l%3A5"),
            "expected adapted limit in uri: $executedQueryUri",
        )
        assertTrue(executedQueryUri!!.contains("includeTraces=true"))
        assertTrue(executedQueryUri!!.contains("includeEvents=true"))
    }

    @Test
    fun `executeQuery uses explicit remote data store without looking up the first one`() {
        val loginBody = """{"authorizationToken":"token-xyz"}"""
        val queryBody = """{"data":[]}"""

        var executedQueryUri: String? = null
        val transport = transportFor(
            mapOf(
                "/api/users/session" to { stubResponse(200, loginBody) },
                "/api/data-stores/ds-explicit/logs" to { req ->
                    executedQueryUri = req.uri().toString()
                    stubResponse(200, queryBody)
                },
            ),
        )
        val response = client(transport).executeQuery(
            "select e:name",
            remoteDataStoreId = "ds-explicit",
            includeTraces = false,
            includeEvents = false,
        )

        assertTrue(response.success)
        assertTrue(executedQueryUri!!.contains("/api/data-stores/ds-explicit/logs"))
        assertEquals("ds-explicit", response.remoteDataStoreId)
    }

    @Test
    fun `listDataStores returns remote store summaries`() {
        val transport = transportFor(
            mapOf(
                "/api/users/session" to { stubResponse(200, """{"authorizationToken":"t"}""") },
                "/api/data-stores" to {
                    stubResponse(
                        200,
                        """[{"id":"ds-1","name":"teleclaims"},{"id":"ds-2","name":"journal"}]""",
                    )
                },
            ),
        )
        assertEquals(
            listOf(
                RemoteProcessMDataStore("ds-1", "teleclaims"),
                RemoteProcessMDataStore("ds-2", "journal"),
            ),
            client(transport).listDataStores(),
        )
    }

    @Test
    fun `uploadLog creates remote data store and posts multipart file through injected rest client`() {
        val transport = transportFor(
            mapOf(
                "/api/users/session" to { stubResponse(200, """{"authorizationToken":"token-xyz"}""") },
                "/api/data-stores" to { stubResponse(201, """{"id":"ds-created"}""") },
            ),
        )
        val rest = restOperations()
        var capturedUrl: String? = null
        var capturedEntity: HttpEntity<*>? = null

        `when`(
            rest.postForEntity(
                Mockito.anyString(),
                anyArg<Any>(),
                Mockito.eq(String::class.java),
            ),
        ).thenAnswer { invocation ->
            capturedUrl = invocation.getArgument(0)
            capturedEntity = invocation.getArgument(1)
            ResponseEntity.ok("uploaded")
        }

        val response = client(transport, rest).uploadLog(
            bytes = byteArrayOf(1, 2, 3),
            originalFilename = "teleclaims.xes.gz",
            logName = "teleclaims.xes.gz",
        )

        assertTrue(response.success, response.message)
        assertEquals("$baseUrl/data-stores/ds-created/logs", capturedUrl)
        assertEquals("Bearer token-xyz", capturedEntity!!.headers.getFirst("Authorization"))
        assertEquals(MediaType.MULTIPART_FORM_DATA, capturedEntity!!.headers.contentType)
        assertTrue(capturedEntity!!.body is MultiValueMap<*, *>)
    }

    @Test
    fun `executeQuery returns error when there are no data stores`() {
        val transport = transportFor(
            mapOf(
                "/api/users/session" to { stubResponse(200, """{"authorizationToken":"t"}""") },
                "/api/data-stores" to { stubResponse(200, "[]") },
            ),
        )
        val response = client(transport).executeQuery("select e:name", includeTraces = false, includeEvents = false)
        assertFalse(response.success)
        assertTrue(response.message.contains("No data stores"), response.message)
    }

    @Test
    fun `executeQuery surfaces API errors as unsuccessful responses`() {
        val transport = transportFor(
            mapOf(
                "/api/users/session" to { stubResponse(200, """{"authorizationToken":"t"}""") },
                "/api/data-stores" to { stubResponse(200, """[{"id":"ds-1"}]""") },
                "/api/data-stores/ds-1/logs" to { stubResponse(500, "boom") },
            ),
        )
        val response = client(transport).executeQuery("select e:name", includeTraces = false, includeEvents = false)
        assertFalse(response.success)
        assertTrue(response.message.contains("500"))
        assertTrue(response.requestUrl!!.contains("/api/data-stores/ds-1/logs"))
    }

    @Test
    fun `login failure propagates as exception response`() {
        val transport = transportFor(
            mapOf(
                "/api/users/session" to { stubResponse(401, "unauthorized") },
            ),
        )
        val response = client(transport).executeQuery("select e:name", includeTraces = false, includeEvents = false)
        assertFalse(response.success)
        assertTrue(response.message.contains("Login failed") || response.message.contains("401"))
    }

    @Test
    fun `adaptQueryLimit adds log scope to unscoped limit clauses`() {
        val client = client(HttpTransport { stubResponse(200, "") })
        assertEquals("select e:name limit l:30", client.adaptQueryLimit("select e:name limit 30"))
        assertEquals("select e:name limit l:10", client.adaptQueryLimit("select e:name limit l:10"))
        assertEquals("select e:name", client.adaptQueryLimit("select e:name"))
    }

    private fun <T> anyArg(): T = Mockito.any<T>()
}
