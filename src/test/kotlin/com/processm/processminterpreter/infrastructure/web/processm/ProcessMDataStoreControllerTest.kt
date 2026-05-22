package com.processm.processminterpreter.infrastructure.web.processm

import com.fasterxml.jackson.databind.ObjectMapper
import com.processm.processminterpreter.application.datastore.CreateDataStoreUseCase
import com.processm.processminterpreter.application.datastore.DeleteDataStoreUseCase
import com.processm.processminterpreter.application.datastore.GetDataStoreUseCase
import com.processm.processminterpreter.application.datastore.ListDataStoreLogsUseCase
import com.processm.processminterpreter.application.datastore.ListDataStoresUseCase
import com.processm.processminterpreter.application.datastore.RenameDataStoreUseCase
import com.processm.processminterpreter.application.ports.DataStoreLogSummary
import com.processm.processminterpreter.application.log.DeleteLogUseCase
import com.processm.processminterpreter.application.log.ImportXesLogRequest
import com.processm.processminterpreter.application.log.ImportXesLogUseCase
import com.processm.processminterpreter.application.query.ExecutePqlQueryRequest
import com.processm.processminterpreter.application.query.ExecutePqlQueryUseCase
import com.processm.processminterpreter.application.query.ExportQueryAsXesUseCase
import com.processm.processminterpreter.application.query.QueryResult
import com.processm.processminterpreter.domain.datastore.DataStore
import com.processm.processminterpreter.application.ports.LogImportResult
import com.processm.processminterpreter.application.processm.QueryJsonProjection
import com.processm.processminterpreter.application.processm.ProcessMXesJsonFormatter
import com.processm.processminterpreter.domain.pql.plan.HierarchicalLimits
import com.processm.processminterpreter.infrastructure.config.ProcessMConfig
import com.processm.processminterpreter.infrastructure.web.processm.dto.ProcessMDataStoreRequest
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
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
    private lateinit var createDataStore: CreateDataStoreUseCase

    @MockitoBean
    private lateinit var listDataStores: ListDataStoresUseCase

    @MockitoBean
    private lateinit var listDataStoreLogs: ListDataStoreLogsUseCase

    @MockitoBean
    private lateinit var getDataStore: GetDataStoreUseCase

    @MockitoBean
    private lateinit var renameDataStore: RenameDataStoreUseCase

    @MockitoBean
    private lateinit var deleteDataStore: DeleteDataStoreUseCase

    @MockitoBean
    private lateinit var deleteLog: DeleteLogUseCase

    @MockitoBean
    private lateinit var importXesLog: ImportXesLogUseCase

    @MockitoBean
    private lateinit var executeQuery: ExecutePqlQueryUseCase

    @MockitoBean
    private lateinit var exportQueryAsXes: ExportQueryAsXesUseCase

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
        `when`(createDataStore.create(com.processm.processminterpreter.application.datastore.CreateDataStoreRequest("Teleclaims")))
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
        `when`(getDataStore.get("ds-1")).thenReturn(dataStore)
        `when`(importXesLog.import(anyImportRequest())).thenReturn(
            LogImportResult(success = true, logId = "log-1", message = "ok"),
        )

        val file = MockMultipartFile("file", "teleclaims.xes.gz", "application/gzip", byteArrayOf(1, 2, 3))

        mockMvc.perform(multipart("/api/data-stores/ds-1/logs").file(file))
            .andExpect(status().isCreated)
    }

    @Test
    fun `log summaries expose lightweight data store contents for local UI`() {
        `when`(listDataStoreLogs.list("ds-1")).thenReturn(
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
        `when`(listDataStoreLogs.list("ds-1")).thenReturn(
            listOf(
                DataStoreLogSummary(
                    logId = "teleclaims",
                    name = "teleclaims.mxml",
                    createdAt = null,
                    updatedAt = null,
                ),
            ),
        )
        `when`(deleteLog.deleteWithData("teleclaims")).thenReturn(true)

        mockMvc.perform(delete("/api/data-stores/ds-1/logs/teleclaims"))
            .andExpect(status().isNoContent)

        verify(deleteLog).deleteWithData("teleclaims")
    }

    @Test
    fun `query logs executes PQL scoped to selected data store`() {
        val execution = QueryResult(logs = emptyList(), rows = emptyList(), rowCount = 0)
        `when`(getDataStore.get("ds-1")).thenReturn(dataStore)
        val defaultLimits = ProcessMConfig.DefaultLimits().apply { enabled = false }
        `when`(processMConfig.defaultLimits).thenReturn(defaultLimits)
        `when`(
            executeQuery.execute(
                ExecutePqlQueryRequest(
                    query = "select e:name",
                    dataStoreId = "ds-1",
                    defaultLimits = HierarchicalLimits(),
                ),
            ),
        ).thenReturn(execution)
        `when`(formatter.formatAsXesJson(QueryJsonProjection(logs = emptyList(), rows = emptyList())))
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

        verify(executeQuery).execute(
            ExecutePqlQueryRequest(
                query = "select e:name",
                dataStoreId = "ds-1",
                defaultLimits = HierarchicalLimits(),
            ),
        )
    }

    private fun anyImportRequest(): ImportXesLogRequest {
        any(ImportXesLogRequest::class.java)
        return ImportXesLogRequest(ByteArrayInputStream(ByteArray(0)))
    }
}
