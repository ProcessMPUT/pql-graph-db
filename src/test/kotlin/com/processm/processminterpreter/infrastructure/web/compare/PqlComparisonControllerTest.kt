package com.processm.processminterpreter.infrastructure.web.compare

import com.processm.processminterpreter.application.ports.RemoteProcessMDataStore
import com.processm.processminterpreter.application.ports.RemoteProcessMGateway
import com.processm.processminterpreter.application.compatibility.VerifyPqlQueryRequest as VerifyPqlQueryUseCaseRequest
import com.processm.processminterpreter.application.compatibility.VerifyPqlQueryResult
import com.processm.processminterpreter.application.compatibility.VerifyPqlQueryUseCase
import com.processm.processminterpreter.domain.pql.common.HierarchicalLimits
import com.processm.processminterpreter.infrastructure.config.ProcessMConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(PqlComparisonController::class)
@Import(PqlComparisonResponseMapper::class)
class PqlComparisonControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var verifyQueryUseCase: VerifyPqlQueryUseCase

    @MockitoBean
    private lateinit var remoteProcessM: RemoteProcessMGateway

    @MockitoBean
    private lateinit var processMConfig: ProcessMConfig

    private fun <T> anyArg(): T = org.mockito.ArgumentMatchers.any<T>()

    @Test
    fun `verifyQuery accepts data store id without requiring logName`() {
        val defaultLimits = ProcessMConfig.DefaultLimits().apply { enabled = false }
        var capturedRequest: VerifyPqlQueryUseCaseRequest? = null

        `when`(processMConfig.defaultLimits).thenReturn(defaultLimits)
        `when`(verifyQueryUseCase.verify(anyArg<VerifyPqlQueryUseCaseRequest>()))
            .thenAnswer { invocation ->
                capturedRequest = invocation.getArgument(0)
                VerifyPqlQueryResult(
                    match = true,
                    localSuccess = true,
                    remoteSuccess = true,
                    details = "ok",
                )
            }

        mockMvc
            .perform(
                post("/api/query/verify")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "query": "limit l:1",
                          "dataStoreId": "store-1",
                          "remoteDataStoreId": "remote-store-1",
                          "includeTraces": true,
                          "includeEvents": false
                        }
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.match").value(true))

        assertEquals("limit l:1", capturedRequest!!.query)
        assertEquals("store-1", capturedRequest!!.dataStoreId)
        assertEquals("remote-store-1", capturedRequest!!.remoteDataStoreId)
        assertEquals(null, capturedRequest!!.logId)
        assertEquals(true, capturedRequest!!.includeTraces)
        assertEquals(false, capturedRequest!!.includeEvents)
        assertEquals(HierarchicalLimits(), capturedRequest!!.defaultLimits)
    }

    @Test
    fun `listRemoteProcessMDataStores exposes remote store summaries`() {
        `when`(remoteProcessM.listDataStores()).thenReturn(
            listOf(
                RemoteProcessMDataStore("remote-1", "Teleclaims"),
                RemoteProcessMDataStore("remote-2", "Journal"),
            ),
        )

        mockMvc
            .perform(get("/api/query/processm/data-stores"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].id").value("remote-1"))
            .andExpect(jsonPath("$[0].name").value("Teleclaims"))
            .andExpect(jsonPath("$[1].id").value("remote-2"))
            .andExpect(jsonPath("$[1].name").value("Journal"))
    }
}
