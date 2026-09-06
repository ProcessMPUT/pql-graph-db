package com.processm.processminterpreter.benchmark

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkSessionTest {
    private val reserve = Duration.ofMinutes(35)

    @Test
    fun `long paired block renews repeatedly before pairs and records no token`(@TempDir directory: Path) {
        Fixture().use { local -> Fixture().use { reference ->
            local.authStatus = 404
            local.client.authenticateIfSupported(allowUnsupported = true)
            reference.client.authenticateIfSupported()
            reference.enforceSessionExpiry = true
            val clients = linkedMapOf(
                BenchmarkSystem("local", "unused", emptySet()) to local.client,
                BenchmarkSystem("reference", "unused", emptySet()) to reference.client,
            )
            val journal = BenchmarkJournal(directory, "long-block")
            val steps = buildQueryExecutionPlan(40, 30)
            for ((index, step) in steps.withIndex()) {
                if (index % 2 == 0) maintainBenchmarkSessions(clients, journal)
                clients.values.elementAt(step.systemIndex).executeQuery("store", "limit l:1")
                if (index % 2 == 1) {
                    // Simulate 11 h 40 min of paired work without waiting or contacting benchmark services.
                    local.clock.advance(Duration.ofMinutes(10))
                    reference.clock.advance(Duration.ofMinutes(10))
                }
            }
            assertEquals(70, local.queryCalls.get())
            assertEquals(70, reference.queryCalls.get())
            assertEquals(1, local.authCalls.get())
            assertEquals(5, reference.authCalls.get())
            val evidence = Files.readString(directory.resolve("execution.jsonl"))
            val renewals = evidence.lineSequence().filter(String::isNotBlank)
                .map { jacksonObjectMapper().readTree(it) }.filter { it["kind"].asText() == "session-renewed" }.toList()
            assertEquals(4, renewals.size)
            assertTrue(renewals.all { it["data"]["system"].asText() == "reference" })
            assertTrue(renewals.all { it["data"]["minimumValiditySeconds"].asLong() == reserve.seconds })
            for (secret in listOf("authorizationToken", "signature", "test-user", "test-password"))
                assertFalse(evidence.contains(secret))
        } }
    }

    @Test
    fun `proactive renewal changes bearer before query without authentication inside query`() {
        Fixture().use { f ->
            f.client.authenticateIfSupported()
            assertFalse(f.client.ensureSessionValid(reserve))
            assertEquals(1, f.authCalls.get())
            f.client.executeQuery("store", "limit l:1")
            val firstBearer = f.lastQueryAuthorization

            f.clock.advance(Duration.ofHours(3))
            assertTrue(f.client.ensureSessionValid(reserve))
            assertEquals(2, f.authCalls.get())
            f.client.executeQuery("store", "limit l:1")
            assertEquals(2, f.authCalls.get())
            assertEquals(2, f.queryCalls.get())
            assertTrue(firstBearer != f.lastQueryAuthorization)
            assertFalse(f.client.ensureSessionValid(reserve))
        }
    }

    @Test
    fun `failed renewal stops before query and never exposes authentication response`() {
        Fixture().use { f ->
            f.client.authenticateIfSupported()
            f.clock.advance(Duration.ofHours(3))
            f.authStatus = 401
            f.authBody = "private authentication response"
            val failure = assertFailsWith<IllegalStateException> {
                f.client.ensureSessionValid(reserve)
                f.client.executeQuery("store", "limit l:1")
            }
            assertTrue(failure.message.orEmpty().contains("HTTP 401"))
            assertFalse(failure.message.orEmpty().contains(f.authBody!!))
            assertEquals(2, f.authCalls.get())
            assertEquals(0, f.queryCalls.get())
        }
    }

    @Test
    fun `query unauthorized response is a failure without automatic authentication or replay`() {
        Fixture().use { f ->
            f.client.authenticateIfSupported()
            f.queryStatus = 401
            val failure = assertFailsWith<BenchmarkRequestException> {
                f.client.executeQuery("store", "limit l:1")
            }
            assertEquals(401, failure.statusCode)
            assertEquals(1, f.authCalls.get())
            assertEquals(1, f.queryCalls.get())
        }
    }

    @Test
    fun `only explicitly optional authentication accepts unsupported endpoint`() {
        for (status in listOf(404, 405)) {
            Fixture().use { f ->
                f.authStatus = status
                assertFailsWith<IllegalStateException> { f.client.authenticateIfSupported() }
                f.client.authenticateIfSupported(allowUnsupported = true)
                assertFalse(f.client.ensureSessionValid(reserve))
                f.client.executeQuery("store", "limit l:1")
                assertEquals(null, f.lastQueryAuthorization)
                assertEquals(2, f.authCalls.get())
            }
        }
        for (status in listOf(401, 500)) Fixture().use { f ->
            f.authStatus = status
            assertFailsWith<IllegalStateException> { f.client.authenticateIfSupported(allowUnsupported = true) }
        }
    }

    @Test
    fun `successful authentication requires valid future expiry and renewal must cover reserve`() {
        Fixture().use { f ->
            for (body in listOf("", "{}", """{"authorizationToken":"private-token"}""",
                """{"authorizationToken":"${token("""{"exp":"invalid"}""")}"}""")) {
                f.authBody = body
                val failure = assertFailsWith<IllegalStateException> { f.client.authenticateIfSupported() }
                assertFalse(failure.message.orEmpty().contains("private-token"))
            }
            f.authBody = null
            f.ttl = Duration.ZERO
            assertFailsWith<IllegalStateException> { f.client.authenticateIfSupported() }
            f.ttl = Duration.ofHours(3)
            f.client.authenticateIfSupported()
            f.clock.advance(Duration.ofMinutes(150))
            f.ttl = Duration.ofMinutes(30)
            assertFailsWith<IllegalStateException> {
                f.client.ensureSessionValid(reserve)
                f.client.executeQuery("store", "limit l:1")
            }
            assertEquals(0, f.queryCalls.get())
        }
    }

    private class MutableClock : Clock() {
        @Volatile private var now = Instant.parse("2026-09-06T00:00:00Z")
        override fun instant(): Instant = now
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = fixed(now, zone)
        fun advance(duration: Duration) { now = now.plus(duration) }
    }

    private class Fixture : AutoCloseable {
        val clock = MutableClock()
        val authCalls = AtomicInteger()
        val queryCalls = AtomicInteger()
        @Volatile var authStatus = 201
        @Volatile var authBody: String? = null
        @Volatile var ttl: Duration = Duration.ofHours(3)
        @Volatile var queryStatus = 200
        @Volatile var enforceSessionExpiry = false
        @Volatile var lastQueryAuthorization: String? = null
        private var lastIssuedToken: String? = null
        private var lastIssuedExpiration: Instant? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/users/session") { exchange ->
                authCalls.incrementAndGet()
                val expiration = clock.instant().plus(ttl)
                val issuedToken = token("""{"exp":${expiration.epochSecond}}""")
                val body = authBody ?: """{"authorizationToken":"$issuedToken"}"""
                if (authStatus in 200..299 && authBody == null) {
                    lastIssuedToken = issuedToken
                    lastIssuedExpiration = expiration
                }
                exchange.respond(authStatus, body)
            }
            createContext("/api/data-stores/store/logs") { exchange ->
                queryCalls.incrementAndGet()
                lastQueryAuthorization = exchange.requestHeaders.getFirst("Authorization")
                val expired = lastIssuedExpiration?.isAfter(clock.instant()) != true ||
                    lastQueryAuthorization != "Bearer $lastIssuedToken"
                exchange.respond(if (enforceSessionExpiry && expired) 401 else queryStatus, "[]")
            }
            start()
        }
        val client = BenchmarkHttpClient("http://127.0.0.1:${server.address.port}/api", "test-user", "test-password", clock)
        override fun close() { server.stop(0) }
        private fun HttpExchange.respond(status: Int, body: String) {
            requestBody.use { it.readBytes() }
            val bytes = body.toByteArray()
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
            close()
        }
    }

    companion object {
        private fun token(payload: String): String {
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())
            return "eyJhbGciOiJIUzUxMiJ9.$encoded.signature"
        }
    }
}
