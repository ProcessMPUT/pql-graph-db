package com.processm.processminterpreter.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.processm.processminterpreter.service.PQLQueryResult
import com.processm.processminterpreter.service.PQLQueryService
import com.processm.processminterpreter.service.PQLValidationResult
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(PQLQueryController::class)
class PQLQueryControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var pqlQueryService: PQLQueryService

    @MockitoBean
    private lateinit var remoteProcessMService: com.processm.processminterpreter.service.RemoteProcessMService

    @MockitoBean
    private lateinit var processMConfig: com.processm.processminterpreter.config.ProcessMConfig

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `executeQuery should return success response`() {
        val query = "select * from event"
        val request = PQLQueryRequest(query)
        val result = PQLQueryResult(
            success = true,
            query = query,
            cypherQuery = "MATCH (n) RETURN n",
            results = listOf(mapOf("id" to 1)),
            resultCount = 1
        )

        `when`(pqlQueryService.executePQLQuery(query, null)).thenReturn(result)

        mockMvc.perform(post("/api/query/execute")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.resultCount").value(1))
            .andExpect(jsonPath("$.cypherQuery").value("MATCH (n) RETURN n"))
    }

    @Test
    fun `executeQuery should return error response on failure`() {
        val query = "invalid query"
        val request = PQLQueryRequest(query)
        val result = PQLQueryResult(
            success = false,
            query = query,
            error = "Syntax error"
        )

        `when`(pqlQueryService.executePQLQuery(query, null)).thenReturn(result)

        mockMvc.perform(post("/api/query/execute")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error").value("Syntax error"))
    }

    @Test
    fun `validateQuery should return valid response`() {
        val query = "select * from event"
        val request = PQLValidationRequest(query)
        val result = PQLValidationResult(
            valid = true,
            query = query,
            cypherQuery = "MATCH (n) RETURN n",
            message = "Valid"
        )

        `when`(pqlQueryService.validatePQLQuery(query)).thenReturn(result)

        mockMvc.perform(post("/api/query/validate")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.valid").value(true))
            .andExpect(jsonPath("$.cypherQuery").value("MATCH (n) RETURN n"))
    }

    @Test
    fun `executeQueryAsXES should return XES file`() {
        val query = "select * from event"
        val request = PQLQueryRequest(query)
        val xesContent = "<log></log>".toByteArray()

        `when`(pqlQueryService.executePQLQueryAsXES(query, null, false, "Query Result Log"))
            .thenReturn(xesContent)

        mockMvc.perform(post("/api/query/execute-xes")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "application/xml"))
            .andExpect(content().bytes(xesContent))
    }

    @Test
    fun `getQueryStatistics should return statistics`() {
        val stats = mapOf("totalQueries" to 10)
        `when`(pqlQueryService.getQueryStatistics()).thenReturn(stats)

        mockMvc.perform(get("/api/query/statistics"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalQueries").value(10))
    }

    @Test
    fun `getSupportedFeatures should return features`() {
        mockMvc.perform(get("/api/query/features"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.supportedClauses").isArray)
            .andExpect(jsonPath("$.supportedOperators").isArray)
    }
}
