package com.processm.processminterpreter.application.log

import com.processm.processminterpreter.domain.log.Log
import com.processm.processminterpreter.domain.datastore.DataStore
import com.processm.processminterpreter.application.ports.BundledLogResourceReader
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

class ImportSampleXesLogUseCaseTest {

    private class FakeImporter(
        var result: LogImportResult = LogImportResult(
            success = true,
            logId = "generated",
            traceCount = 3,
            eventCount = 42,
            message = "ok",
        ),
    ) : LogDataImporter {
        val calls = mutableListOf<String?>()

        override fun import(input: InputStream, logId: String?): LogImportResult {
            calls += logId
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

    private class FakeBundledLogResourceReader(
        private val available: Boolean = true,
    ) : BundledLogResourceReader {
        override fun open(resourcePath: String): InputStream? =
            if (available) ByteArrayInputStream("<log/>".toByteArray()) else null
    }

    @Test
    fun `import opens sample resource and passes through importer result`() {
        val importer = FakeImporter()
        val useCase = ImportSampleXesLogUseCase(
            importer,
            FakeLogRepository(),
            FakeDataStoreRepository(),
            FakeBundledLogResourceReader(),
        )

        val result = useCase.import(
            ImportSampleXesLogRequest(resourcePath = "logs/sample_process.xes", logId = "my-log"),
        )

        assertTrue(result.success)
        assertEquals("generated", result.logId)
        assertEquals(3, result.traceCount)
        assertEquals(42, result.eventCount)
        assertEquals(listOf("my-log"), importer.calls)
    }

    @Test
    fun `import rejects a duplicate logId before touching the importer`() {
        val importer = FakeImporter()
        val useCase = ImportSampleXesLogUseCase(
            importer,
            FakeLogRepository(existing = setOf("existing")),
            FakeDataStoreRepository(),
            FakeBundledLogResourceReader(),
        )

        val result = useCase.import(
            ImportSampleXesLogRequest(resourcePath = "logs/sample_process.xes", logId = "existing"),
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("already exists"))
        assertEquals(0, importer.calls.size)
    }

    @Test
    fun `blank logId is treated as no logId`() {
        val importer = FakeImporter()
        val useCase = ImportSampleXesLogUseCase(
            importer,
            FakeLogRepository(),
            FakeDataStoreRepository(),
            FakeBundledLogResourceReader(),
        )

        useCase.import(ImportSampleXesLogRequest(resourcePath = "logs/sample_process.xes", logId = "   "))

        assertEquals(listOf(null), importer.calls)
    }

    @Test
    fun `successful sample import attaches imported log to data store`() {
        val importer = FakeImporter(
            result = LogImportResult(
                success = true,
                logId = "teleclaims",
                traceCount = 3,
                eventCount = 42,
                message = "ok",
            ),
        )
        val dataStores = FakeDataStoreRepository(existing = setOf("store-1"))
        val useCase = ImportSampleXesLogUseCase(
            importer,
            FakeLogRepository(),
            dataStores,
            FakeBundledLogResourceReader(),
        )

        val result = useCase.import(
            ImportSampleXesLogRequest(
                resourcePath = "logs/sample_process.xes",
                logId = "teleclaims",
                dataStoreId = "store-1",
            ),
        )

        assertTrue(result.success)
        assertEquals(listOf("teleclaims"), importer.calls)
        assertEquals(listOf("store-1" to "teleclaims"), dataStores.attached)
    }
}
