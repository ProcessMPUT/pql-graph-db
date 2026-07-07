package com.processm.processminterpreter.web.processm

import com.fasterxml.jackson.databind.ObjectMapper
import com.processm.processminterpreter.xes.datastore.DataStoreService
import com.processm.processminterpreter.xes.DataStoreLogSummary
import com.processm.processminterpreter.xes.ImportXesLogRequest
import com.processm.processminterpreter.xes.LogService
import com.processm.processminterpreter.pql.ExecutePqlQueryRequest
import com.processm.processminterpreter.pql.PqlQueryService
import com.processm.processminterpreter.pql.QueryResult
import com.processm.processminterpreter.xes.datastore.DataStore
import com.processm.processminterpreter.xes.LogImportResult
import com.processm.processminterpreter.processm.json.QueryJsonProjection
import com.processm.processminterpreter.processm.json.ProcessMXesJsonFormatter
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import com.processm.processminterpreter.processm.ProcessMConfig
import com.processm.processminterpreter.web.processm.dto.ProcessMDataStoreRequest
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.ByteArrayInputStream
import java.time.LocalDateTime

@WebMvcTest(ProcessMDataStoreController::class)
class ProcessMDataStoreControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var dataStores: DataStoreService

    @MockitoBean
    private lateinit var logs: LogService

    @MockitoBean
    private lateinit var pqlQueryService: PqlQueryService

    @MockitoBean
    private lateinit var formatter: ProcessMXesJsonFormatter

    @MockitoBean
    private lateinit var processMConfig: ProcessMConfig

    private val dataStore = DataStore(
        id = "ds-1",
        name = "Teleclaims",
        createdAt = LocalDateTime.of(2026, 4, 24, 12, 0),
        updatedAt = LocalDateTime.of(2026, 4, 24, 12, 0),
    )

    @Test
    fun `create data store exposes ProcessM-compatible path`() {
        `when`(dataStores.create(com.processm.processminterpreter.xes.datastore.CreateDataStoreRequest("Teleclaims")))
            .thenReturn(dataStore)

        mockMvc.perform(
            post("/api/data-stores")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(ProcessMDataStoreRequest("Teleclaims"))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value("ds-1"))
            .andExpect(jsonPath("$.name").value("Teleclaims"))
    }

    @Test
    fun `upload log imports into selected data store`() {
        `when`(dataStores.get("ds-1")).thenReturn(dataStore)
        `when`(logs.importXes(anyImportRequest())).thenReturn(
            LogImportResult(success = true, logId = "log-1", message = "ok"),
        )

        val file = MockMultipartFile("file", "teleclaims.xes.gz", "application/gzip", byteArrayOf(1, 2, 3))

        mockMvc.perform(multipart("/api/data-stores/ds-1/logs").file(file))
            .andExpect(status().isCreated)
    }

    @Test
    fun `log summaries expose lightweight data store contents for local UI`() {
        `when`(dataStores.listLogs("ds-1")).thenReturn(
            listOf(
                DataStoreLogSummary(
                    logId = "teleclaims",
                    name = "teleclaims.mxml",
                    createdAt = LocalDateTime.of(2026, 4, 29, 21, 46, 49),
                    updatedAt = LocalDateTime.of(2026, 4, 29, 21, 46, 49),
                ),
            ),
        )

        mockMvc.perform(get("/api/data-stores/ds-1/log-summaries").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].logId").value("teleclaims"))
            .andExpect(jsonPath("$[0].name").value("teleclaims.mxml"))
    }

    @Test
    fun `delete log removes only logs attached to selected data store`() {
        `when`(dataStores.listLogs("ds-1")).thenReturn(
            listOf(
                DataStoreLogSummary(
                    logId = "teleclaims",
                    name = "teleclaims.mxml",
                    createdAt = null,
                    updatedAt = null,
                ),
            ),
        )
        `when`(logs.deleteWithData("teleclaims")).thenReturn(true)

        mockMvc.perform(delete("/api/data-stores/ds-1/logs/teleclaims"))
            .andExpect(status().isNoContent)

        verify(logs).deleteWithData("teleclaims")
    }

    @Test
    fun `query logs executes PQL scoped to selected data store`() {
        val execution = QueryResult(logs = emptyList(), rows = emptyList(), rowCount = 0)
        `when`(dataStores.get("ds-1")).thenReturn(dataStore)
        val defaultLimits = ProcessMConfig.DefaultLimits().apply { enabled = false }
        `when`(processMConfig.defaultLimits).thenReturn(defaultLimits)
        `when`(
            pqlQueryService.executeRead(
                ExecutePqlQueryRequest(
                    query = "select e:name",
                    dataStoreId = "ds-1",
                    defaultLimits = HierarchicalLimits(),
                ),
            ),
        ).thenReturn(execution)
        `when`(formatter.formatAsXesJson(QueryJsonProjection(logs = emptyList())))
            .thenReturn(listOf(mapOf("log" to emptyMap<String, Any>())))

        mockMvc.perform(
            get("/api/data-stores/ds-1/logs")
                .param("query", "select e:name")
                .param("includeTraces", "true")
                .param("includeEvents", "true")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].log").exists())

        // Read-only path: the GET endpoint must never route through the
        // mutating execute()/executeDelete() — it uses executeRead.
        verify(pqlQueryService).executeRead(
            ExecutePqlQueryRequest(
                query = "select e:name",
                dataStoreId = "ds-1",
                defaultLimits = HierarchicalLimits(),
            ),
        )
    }

    @Test
    fun `delete query on the read endpoint is rejected with 400, not executed`() {
        `when`(dataStores.get("ds-1")).thenReturn(dataStore)
        val defaultLimits = ProcessMConfig.DefaultLimits().apply { enabled = false }
        `when`(processMConfig.defaultLimits).thenReturn(defaultLimits)
        `when`(pqlQueryService.executeRead(anyArg<ExecutePqlQueryRequest>()))
            .thenThrow(IllegalArgumentException("DELETE is not allowed on a read/query endpoint"))

        mockMvc.perform(
            get("/api/data-stores/ds-1/logs")
                .param("query", "delete where l:name = 'x'")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("not allowed")))
    }

    private fun <T> anyArg(): T = org.mockito.ArgumentMatchers.any<T>()

    private fun anyImportRequest(): ImportXesLogRequest {
        any(ImportXesLogRequest::class.java)
        return ImportXesLogRequest(ByteArrayInputStream(ByteArray(0)))
    }
}
