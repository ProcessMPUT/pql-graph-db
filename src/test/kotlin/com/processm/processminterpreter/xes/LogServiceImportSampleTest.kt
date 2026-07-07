package com.processm.processminterpreter.xes

import com.processm.processminterpreter.pql.FakeDataStoreRepository
import com.processm.processminterpreter.pql.FakeLogRepository
import com.processm.processminterpreter.pql.logStub
import com.processm.processminterpreter.xes.LogImportResult
import com.processm.processminterpreter.xes.io.ClasspathBundledLogResourceReader
import com.processm.processminterpreter.xes.io.XESLoader
import com.processm.processminterpreter.xes.io.XesLogImporter
import org.mockito.Mockito
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class LogServiceImportSampleTest {

    private class FakeImporter(
        var result: LogImportResult = LogImportResult(
            success = true,
            logId = "generated",
            traceCount = 3,
            eventCount = 42,
            message = "ok",
        ),
    ) : XesLogImporter(Mockito.mock(XESLoader::class.java)) {
        val calls = mutableListOf<String?>()

        override fun import(input: InputStream, logId: String?): LogImportResult {
            calls += logId
            return result
        }
    }

    private class FakeBundledLogResourceReader(
        private val available: Boolean = true,
    ) : ClasspathBundledLogResourceReader() {
        override fun open(resourcePath: String): InputStream? =
            if (available) ByteArrayInputStream("<log/>".toByteArray()) else null
    }

    @Test
    fun `import opens sample resource and passes through importer result`() {
        val importer = FakeImporter()
        val useCase = LogService(
            FakeLogRepository(),
            FakeDataStoreRepository(),
            importer,
            FakeBundledLogResourceReader(),
        )

        val result = useCase.importSample(
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
        val useCase = LogService(
            FakeLogRepository(byId = mapOf("existing" to logStub("existing"))),
            FakeDataStoreRepository(),
            importer,
            FakeBundledLogResourceReader(),
        )

        val result = useCase.importSample(
            ImportSampleXesLogRequest(resourcePath = "logs/sample_process.xes", logId = "existing"),
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("already exists"))
        assertEquals(0, importer.calls.size)
    }

    @Test
    fun `blank logId is treated as no logId`() {
        val importer = FakeImporter()
        val useCase = LogService(
            FakeLogRepository(),
            FakeDataStoreRepository(),
            importer,
            FakeBundledLogResourceReader(),
        )

        useCase.importSample(ImportSampleXesLogRequest(resourcePath = "logs/sample_process.xes", logId = "   "))

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
        val dataStores = FakeDataStoreRepository(logsByDataStoreId = mapOf("store-1" to emptyList()))
        val useCase = LogService(
            FakeLogRepository(),
            dataStores,
            importer,
            FakeBundledLogResourceReader(),
        )

        val result = useCase.importSample(
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
