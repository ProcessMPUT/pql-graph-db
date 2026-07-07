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

class LogServiceImportXesTest {

    private class FakeImporter(
        var result: LogImportResult = LogImportResult(
            success = true, logId = "generated", traceCount = 3, eventCount = 42,
            message = "ok",
        ),
    ) : XesLogImporter(Mockito.mock(XESLoader::class.java)) {
        data class Call(val input: InputStream, val logId: String?)

        val calls = mutableListOf<Call>()
        override fun import(input: InputStream, logId: String?): LogImportResult {
            calls += Call(input, logId)
            return result
        }
    }

    private fun xesStream() = ByteArrayInputStream("<xes/>".toByteArray())

    @Test
    fun `import forwards to the LogDataImporter and passes through its result`() {
        val importer = FakeImporter()
        val useCase = LogService(FakeLogRepository(), FakeDataStoreRepository(), importer, ClasspathBundledLogResourceReader())

        val stream = xesStream()
        val result = useCase.importXes(ImportXesLogRequest(input = stream, logId = "my-log"))

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
        val repo = FakeLogRepository(byId = mapOf("existing" to logStub("existing")))
        val useCase = LogService(repo, FakeDataStoreRepository(), importer, ClasspathBundledLogResourceReader())

        val result = useCase.importXes(ImportXesLogRequest(input = xesStream(), logId = "existing"))

        assertFalse(result.success)
        assertTrue(result.error!!.contains("already exists"))
        assertEquals(0, importer.calls.size, "importer should not run for duplicate ids")
    }

    @Test
    fun `blank logId is treated as no logId`() {
        val importer = FakeImporter()
        val useCase = LogService(FakeLogRepository(), FakeDataStoreRepository(), importer, ClasspathBundledLogResourceReader())

        useCase.importXes(ImportXesLogRequest(input = xesStream(), logId = "   "))

        assertEquals(1, importer.calls.size)
        assertEquals(null, importer.calls.single().logId, "blank id must become null upstream")
    }

    @Test
    fun `null logId is forwarded as null`() {
        val importer = FakeImporter()
        val useCase = LogService(FakeLogRepository(), FakeDataStoreRepository(), importer, ClasspathBundledLogResourceReader())

        useCase.importXes(ImportXesLogRequest(input = xesStream()))

        assertEquals(null, importer.calls.single().logId)
    }

    @Test
    fun `importer-reported failure propagates unchanged`() {
        val importer = FakeImporter(
            result = LogImportResult(success = false, message = "bad", error = "malformed XML"),
        )
        val useCase = LogService(FakeLogRepository(), FakeDataStoreRepository(), importer, ClasspathBundledLogResourceReader())

        val result = useCase.importXes(ImportXesLogRequest(input = xesStream()))

        assertFalse(result.success)
        assertEquals("malformed XML", result.error)
    }

    @Test
    fun `import attaches successful log to data store`() {
        val importer = FakeImporter(result = LogImportResult(success = true, logId = "log-1", message = "ok"))
        val dataStores = FakeDataStoreRepository(logsByDataStoreId = mapOf("store-1" to emptyList()))
        val useCase = LogService(FakeLogRepository(), dataStores, importer, ClasspathBundledLogResourceReader())

        val result = useCase.importXes(ImportXesLogRequest(input = xesStream(), dataStoreId = "store-1"))

        assertTrue(result.success)
        assertEquals(null, importer.calls.single().logId)
        assertEquals(listOf("store-1" to "log-1"), dataStores.attached)
    }

    @Test
    fun `import rejects unknown data store before touching importer`() {
        val importer = FakeImporter()
        val useCase = LogService(FakeLogRepository(), FakeDataStoreRepository(), importer, ClasspathBundledLogResourceReader())

        val result = useCase.importXes(ImportXesLogRequest(input = xesStream(), dataStoreId = "missing"))

        assertFalse(result.success)
        assertTrue(result.error!!.contains("does not exist"))
        assertEquals(0, importer.calls.size)
    }
}
