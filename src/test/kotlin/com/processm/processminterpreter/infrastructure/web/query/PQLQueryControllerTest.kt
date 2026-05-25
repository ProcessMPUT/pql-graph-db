package com.processm.processminterpreter.infrastructure.web.query

import com.fasterxml.jackson.databind.ObjectMapper
import com.processm.processminterpreter.application.ports.ProcessMJsonFormatter
import com.processm.processminterpreter.application.ports.QueryJsonProjection
import com.processm.processminterpreter.application.query.ExecutePqlQueryRequest
import com.processm.processminterpreter.application.query.ExecutePqlQueryUseCase
import com.processm.processminterpreter.application.query.ExportQueryAsXesRequest
import com.processm.processminterpreter.application.query.ExportQueryAsXesUseCase
import com.processm.processminterpreter.application.query.ExportResult
import com.processm.processminterpreter.application.query.PqlMetadataUseCase
import com.processm.processminterpreter.application.query.PqlQueryStatisticsSummary
import com.processm.processminterpreter.application.query.QueryResult
import com.processm.processminterpreter.application.query.SupportedPqlFeatures
import com.processm.processminterpreter.application.query.ValidatePqlQueryRequest
import com.processm.processminterpreter.application.query.ValidatePqlQueryUseCase
import com.processm.processminterpreter.application.query.ValidationResult
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.error.PQLSyntaxException
import com.processm.processminterpreter.infrastructure.web.query.dto.PQLQueryRequest
import com.processm.processminterpreter.infrastructure.web.query.dto.PQLValidationRequest
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.OutputStream

@WebMvcTest(PQLQueryController::class)
@Import(PqlResponseMapper::class)
class PQLQueryControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var executeQueryUseCase: ExecutePqlQueryUseCase

    @MockitoBean
    private lateinit var validateQueryUseCase: ValidatePqlQueryUseCase

    @MockitoBean
    private lateinit var exportQueryUseCase: ExportQueryAsXesUseCase

    @MockitoBean
    private lateinit var queryMetadataUseCase: PqlMetadataUseCase

    @MockitoBean
    private lateinit var processMXesJsonFormatter: ProcessMJsonFormatter

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun <T> anyArg(): T = org.mockito.ArgumentMatchers.any<T>()

    @Test
    fun `executeQuery should return success response`() {
        val query = "select * from event"
        val request = PQLQueryRequest(query)
        val result = QueryResult(
            logs = emptyList(),
            rows = listOf(mapOf("id" to 1)),
            rowCount = 1,
            executedQueryDescription = "MATCH (n) RETURN n",
        )

        `when`(
            executeQueryUseCase.execute(
                ExecutePqlQueryRequest(
                    query = query,
                    logId = null,
                    dataStoreId = null,
                    materializedScopes = emptySet(),
                ),
            ),
        ).thenReturn(result)

        mockMvc
            .perform(
                post("/api/query/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.resultCount").value(1))
            .andExpect(jsonPath("$.cypherQuery").value("MATCH (n) RETURN n"))
    }

    @Test
    fun `executeQuery should delegate xes formatting to web response mapper`() {
        val query = "limit l:1"
        val request = PQLQueryRequest(query)
        val result = QueryResult(
            logs = emptyList(),
            rows = listOf(mapOf("id" to 1)),
            rowCount = 1,
        )

        `when`(
            executeQueryUseCase.execute(
                ExecutePqlQueryRequest(
                    query = query,
                    logId = null,
                    dataStoreId = null,
                    materializedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
                ),
            ),
        ).thenReturn(result)
        `when`(
            processMXesJsonFormatter.formatAsXesJson(
                QueryJsonProjection(
                    logs = emptyList(),
                    rows = listOf(mapOf("id" to 1)),
                ),
            ),
        ).thenReturn(listOf(mapOf("log" to mapOf("string" to emptyList<Any>()))))

        mockMvc
            .perform(
                post("/api/query/execute")
                    .param("format", "xes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.results[0].log").exists())
    }

    @Test
    fun `executeQuery should return error response on failure`() {
        val query = "invalid query"
        val request = PQLQueryRequest(query)
        `when`(
            executeQueryUseCase.execute(
                ExecutePqlQueryRequest(
                    query = query,
                    logId = null,
                    dataStoreId = null,
                    materializedScopes = emptySet(),
                ),
            ),
        ).thenThrow(PQLSyntaxException(SourceLocation.UNKNOWN, "Syntax error"))

        mockMvc
            .perform(
                post("/api/query/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").value(containsString("Syntax error")))
    }

    @Test
    fun `validateQuery should return valid response`() {
        val query = "select * from event"
        val request = PQLValidationRequest(query)

        `when`(validateQueryUseCase.validate(ValidatePqlQueryRequest(query = query, logId = null)))
            .thenReturn(ValidationResult(valid = true, query = query))

        mockMvc
            .perform(
                post("/api/query/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.message").value("Query is valid"))
    }

    @Test
    fun `executeQueryAsXES should return XES file`() {
        val query = "select * from event"
        val request = PQLQueryRequest(query)
        val xesContent = "<log></log>".toByteArray()
        var capturedRequest: ExportQueryAsXesRequest? = null

        `when`(exportQueryUseCase.export(anyArg<ExportQueryAsXesRequest>(), anyArg<OutputStream>()))
            .thenAnswer { invocation ->
                capturedRequest = invocation.getArgument(0)
                val out = invocation.getArgument<OutputStream>(1)
                out.write(xesContent)
                ExportResult(logCount = 1, rowCount = 0, executedQueryDescription = "")
            }

        mockMvc
            .perform(
                post("/api/query/execute-xes")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/xml"))
            .andExpect(content().bytes(xesContent))

        assertEquals(false, capturedRequest!!.compress)
    }

    @Test
    fun `executeQueryAsXES delegates compression to export use case`() {
        val query = "select * from event"
        val request = PQLQueryRequest(query)
        val gzippedContent = byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0x00)
        var capturedRequest: ExportQueryAsXesRequest? = null

        `when`(exportQueryUseCase.export(anyArg<ExportQueryAsXesRequest>(), anyArg<OutputStream>()))
            .thenAnswer { invocation ->
                capturedRequest = invocation.getArgument(0)
                invocation.getArgument<OutputStream>(1).write(gzippedContent)
                ExportResult(logCount = 1, rowCount = 0, executedQueryDescription = "")
            }

        mockMvc
            .perform(
                post("/api/query/execute-xes")
                    .param("compress", "true")
                    .param("logName", "audit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)),
            ).andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/gzip"))
            .andExpect(header().string("Content-Disposition", startsWith("attachment; filename=\"query_result_")))
            .andExpect(content().bytes(gzippedContent))

        assertEquals(true, capturedRequest!!.compress)
        assertEquals("audit", capturedRequest!!.logName)
    }

    @Test
    fun `getQueryStatistics should return current query statistics`() {
        `when`(queryMetadataUseCase.statistics()).thenReturn(
            PqlQueryStatisticsSummary(
                totalQueries = 0,
                successfulQueries = 0,
                failedQueries = 0,
                averageExecutionTime = 0.0,
            ),
        )

        mockMvc
            .perform(get("/api/query/statistics"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalQueries").value(0))
            .andExpect(jsonPath("$.successfulQueries").value(0))
            .andExpect(jsonPath("$.failedQueries").value(0))
            .andExpect(jsonPath("$.averageExecutionTime").value(0.0))
    }

    @Test
    fun `getSupportedFeatures should return features`() {
        `when`(queryMetadataUseCase.supportedFeatures()).thenReturn(
            SupportedPqlFeatures(
                supportedClauses = listOf("SELECT", "FROM", "WHERE"),
                supportedOperators = listOf("=", "!=", "LIKE"),
                supportedEntities = listOf("log", "trace", "event"),
                supportedFields = mapOf("log" to listOf("id")),
                limitations = listOf("Subqueries not yet supported"),
                examples = listOf("SELECT * FROM log"),
            ),
        )

        mockMvc
            .perform(get("/api/query/features"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.supportedClauses").isArray)
            .andExpect(jsonPath("$.supportedOperators").isArray)
    }

}
