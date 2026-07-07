package com.processm.processminterpreter.processm

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestOperations
import org.springframework.web.client.RestTemplate
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.net.ssl.SSLParameters

/**
 * Thin injectable seam for HTTP calls - lets tests substitute a canned
 * responder without pulling in a full WireMock/MockWebServer dependency.
 *
 * The default production implementation ([JavaHttpTransport]) wraps
 * `java.net.http.HttpClient` with TLS-1.2/1.3 enforcement.
 */
fun interface HttpTransport {
    fun send(request: HttpRequest): HttpResponse<String>
}

@Component
class JavaHttpTransport : HttpTransport {
    private val client: HttpClient = run {
        val ssl = SSLParameters().apply {
            endpointIdentificationAlgorithm = "HTTPS"
            protocols = arrayOf("TLSv1.3", "TLSv1.2")
        }
        HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .sslParameters(ssl)
            .build()
    }

    /**
     * Bounds every request. Callers do not set per-request timeouts, and an
     * unbounded call to a hung remote ProcessM would block the request thread
     * (and, in VerificationService, a pooled thread) forever — so we apply a
     * request timeout here even when the built [HttpRequest] carries none.
     */
    override fun send(request: HttpRequest): HttpResponse<String> {
        val timedRequest =
            if (request.timeout().isPresent) request
            else HttpRequest.newBuilder(request, { _, _ -> true }).timeout(REQUEST_TIMEOUT).build()
        return client.send(timedRequest, HttpResponse.BodyHandlers.ofString())
    }

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(10)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(120)
    }
}

@Configuration
class ProcessMHttpClientConfig {
    @Bean
    fun processMRestOperations(): RestOperations {
        // Default RestTemplate has infinite connect/read timeouts — a hung remote
        // would block the request thread forever.
        val factory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(10_000)
            setReadTimeout(120_000)
        }
        return RestTemplate(factory)
    }
}
