package com.processm.processminterpreter.application.log

import com.processm.processminterpreter.domain.log.Log
import com.processm.processminterpreter.domain.datastore.DataStore
import com.processm.processminterpreter.application.ports.DataStoreLogSummary
import com.processm.processminterpreter.application.ports.DataStoreRepository
import com.processm.processminterpreter.application.ports.LogDataImporter
import com.processm.processminterpreter.application.ports.LogImportResult
import com.processm.processminterpreter.application.ports.LogRepository
import com.processm.processminterpreter.application.ports.LogStatistics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDateTime

class ImportXesLogUseCaseTest {

    private class FakeImporter(
        var result: LogImportResult = LogImportResult(
            success = true, logId = "generated", traceCount = 3, eventCount = 42,
            message = "ok",
        ),
    ) : LogDataImporter {
        data class Call(val input: InputStream, val logId: String?)

        val calls = mutableListOf<Call>()
        override fun import(input: InputStream, logId: String?): LogImportResult {
            calls += Call(input, logId)
            return result
        }
    }

    private class FakeLogRepository(private val existing: Set<String> = emptySet()) : LogRepository {
        override fun save(log: Log): Log = log
        override fun findById(id: String): Log? = null
        override fun findAll(): List<Log> = emptyList()
        override fun search(namePart: String): List<Log> = emptyList()
        override fun findByAttribute(key: String, value: Any): List<Log> = emptyList()
        override fun findCreatedAfter(date: LocalDateTime): List<Log> = emptyList()
        override fun findCreatedBetween(start: LocalDateTime, end: LocalDateTime): List<Log> = emptyList()
        override fun getStatistics(id: String): LogStatistics? = null
        override fun getStatisticsAll(): List<Pair<Log, LogStatistics>> = emptyList()
        override fun getClassifiers(id: String): Map<String, List<String>> = emptyMap()
        override fun update(log: Log): Log = log
        override fun delete(id: String): Boolean = false
        override fun deleteWithData(id: String): Boolean = false
        override fun exists(id: String): Boolean = id in existing
    }

    private class FakeDataStoreRepository(private val existing: Set<String> = emptySet()) : DataStoreRepository {
        val attached = mutableListOf<Pair<String, String>>()

        override fun save(dataStore: DataStore): DataStore = dataStore
        override fun findById(id: String): DataStore? = null
        override fun findAll(): List<DataStore> = emptyList()
        override fun findLogSummaries(dataStoreId: String): List<DataStoreLogSummary> = emptyList()
        override fun update(dataStore: DataStore): DataStore = dataStore
        override fun deleteWithLogs(id: String): Boolean = false
        override fun exists(id: String): Boolean = id in existing
        override fun attachLog(dataStoreId: String, logId: String) {
            attached += dataStoreId to logId
        }
    }

    private fun xesStream() = ByteArrayInputStream("<xes/>".toByteArray())

    @Test
    fun `import forwards to the LogDataImporter and passes through its result`() {
        val importer = FakeImporter()
        val useCase = ImportXesLogUseCase(importer, FakeLogRepository(), FakeDataStoreRepository())

        val stream = xesStream()
        val result = useCase.import(ImportXesLogRequest(input = stream, logId = "my-log"))

        assertTrue(result.success)
        assertEquals("generated", result.logId)
        assertEquals(3, result.traceCount)
        assertEquals(42, result.eventCount)
        assertEquals(1, importer.calls.size)
        assertEquals("my-log", importer.calls.single().logId)
        assertEquals(stream, importer.calls.single().input)
    }

    @Test
    fun `import rejects a duplicate logId before touching the importer`() {
        val importer = FakeImporter()
        val repo = FakeLogRepository(existing = setOf("existing"))
        val useCase = ImportXesLogUseCase(importer, repo, FakeDataStoreRepository())

        val result = useCase.import(ImportXesLogRequest(input = xesStream(), logId = "existing"))

        assertFalse(result.success)
        assertTrue(result.error!!.contains("already exists"))
        assertEquals(0, importer.calls.size, "importer should not run for duplicate ids")
    }

    @Test
    fun `blank logId is treated as no logId`() {
        val importer = FakeImporter()
        val useCase = ImportXesLogUseCase(importer, FakeLogRepository(), FakeDataStoreRepository())

        useCase.import(ImportXesLogRequest(input = xesStream(), logId = "   "))

        assertEquals(1, importer.calls.size)
        assertEquals(null, importer.calls.single().logId, "blank id must become null upstream")
    }

    @Test
    fun `null logId is forwarded as null`() {
        val importer = FakeImporter()
        val useCase = ImportXesLogUseCase(importer, FakeLogRepository(), FakeDataStoreRepository())

        useCase.import(ImportXesLogRequest(input = xesStream()))

        assertEquals(null, importer.calls.single().logId)
    }

    @Test
    fun `importer-reported failure propagates unchanged`() {
        val importer = FakeImporter(
            result = LogImportResult(success = false, message = "bad", error = "malformed XML"),
        )
        val useCase = ImportXesLogUseCase(importer, FakeLogRepository(), FakeDataStoreRepository())

        val result = useCase.import(ImportXesLogRequest(input = xesStream()))

        assertFalse(result.success)
        assertEquals("malformed XML", result.error)
    }

    @Test
    fun `import attaches successful log to data store`() {
        val importer = FakeImporter(result = LogImportResult(success = true, logId = "log-1", message = "ok"))
        val dataStores = FakeDataStoreRepository(existing = setOf("store-1"))
        val useCase = ImportXesLogUseCase(importer, FakeLogRepository(), dataStores)

        val result = useCase.import(ImportXesLogRequest(input = xesStream(), dataStoreId = "store-1"))

        assertTrue(result.success)
        assertEquals(null, importer.calls.single().logId)
        assertEquals(listOf("store-1" to "log-1"), dataStores.attached)
    }

    @Test
    fun `import rejects unknown data store before touching importer`() {
        val importer = FakeImporter()
        val useCase = ImportXesLogUseCase(importer, FakeLogRepository(), FakeDataStoreRepository())

        val result = useCase.import(ImportXesLogRequest(input = xesStream(), dataStoreId = "missing"))

        assertFalse(result.success)
        assertTrue(result.error!!.contains("does not exist"))
        assertEquals(0, importer.calls.size)
    }
}
