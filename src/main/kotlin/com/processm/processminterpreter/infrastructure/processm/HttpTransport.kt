package com.processm.processminterpreter.infrastructure.processm

import org.springframework.stereotype.Component
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestOperations
import org.springframework.web.client.RestTemplate
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
        HttpClient.newBuilder().sslParameters(ssl).build()
    }

    override fun send(request: HttpRequest): HttpResponse<String> =
        client.send(request, HttpResponse.BodyHandlers.ofString())
}

@Configuration
class ProcessMHttpClientConfig {
    @Bean
    fun processMRestOperations(): RestOperations = RestTemplate()
}
