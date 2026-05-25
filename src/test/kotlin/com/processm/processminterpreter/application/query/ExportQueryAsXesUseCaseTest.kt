package com.processm.processminterpreter.application.query

import com.processm.processminterpreter.domain.log.xes.XesEvent
import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.log.xes.XesTrace
import com.processm.processminterpreter.application.ports.XesWriteOptions
import com.processm.processminterpreter.application.ports.XesWriter
import com.processm.processminterpreter.infrastructure.xes.OpenXesWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class ExportQueryAsXesUseCaseTest {

    // Test double that records what it was asked to write — avoids spinning up
    // the full compiler pipeline just to check orchestration.
    private class StubExecute(private val result: QueryResult) : ExecutePqlQueryUseCase(
        compiler = PqlCompiler(throwingParser(), throwingRepo(), throwingDataStores()),
        executor = throwingExecutor(),
    ) {
        val calls = mutableListOf<ExecutePqlQueryRequest>()
        override fun execute(request: ExecutePqlQueryRequest): QueryResult {
            calls += request
            return result
        }
    }

    private class RecordingWriter : XesWriter {
        var lastLogs: List<XesLog>? = null
        var lastOptions: XesWriteOptions? = null
        override fun write(logs: List<XesLog>, output: OutputStream, options: XesWriteOptions) {
            lastLogs = logs
            lastOptions = options
            output.write("<xes/>".toByteArray())
        }
    }

    private fun sampleResult(): QueryResult = QueryResult(
        logs = listOf(
            XesLog(
                conceptName = "x",
                traces = listOf(XesTrace(events = listOf(XesEvent(conceptName = "e")))),
            ),
        ),
        rowCount = 1,
        executedQueryDescription = "MATCH ...",
    )

    @Test
    fun `export runs the query and writes the XES to the provided stream`() {
        val execute = StubExecute(sampleResult())
        val writer = RecordingWriter()
        val useCase = ExportQueryAsXesUseCase(execute, writer)

        val out = ByteArrayOutputStream()
        val result = useCase.export(
            ExportQueryAsXesRequest(query = "select e:name", logId = "log-1", compress = true, logName = "my-log"),
            out,
        )

        assertEquals(1, result.logCount)
        assertEquals(1, result.rowCount)
        assertTrue(result.executedQueryDescription.contains("MATCH"))
        assertEquals("<xes/>", out.toString(Charsets.UTF_8))

        // Forwarded arguments end-to-end:
        assertEquals(1, execute.calls.size)
        assertEquals("log-1", execute.calls.single().logId)
        assertEquals("my-log", writer.lastOptions!!.logName)
        assertTrue(writer.lastOptions!!.compress)
    }

    @Test
    fun `DELETE queries are rejected before execution`() {
        val execute = StubExecute(sampleResult())
        val writer = RecordingWriter()
        val useCase = ExportQueryAsXesUseCase(execute, writer)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            useCase.export(ExportQueryAsXesRequest(query = " DELETE FROM event "), ByteArrayOutputStream())
        }
        assertTrue(ex.message!!.contains("DELETE", ignoreCase = true))
        assertEquals(0, execute.calls.size, "executor should not run for DELETE queries")
        assertTrue(writer.lastLogs == null, "writer should not be touched")
    }

    @Test
    fun `default request options produce uncompressed XES with the default log name`() {
        val execute = StubExecute(sampleResult())
        val writer = RecordingWriter()
        val useCase = ExportQueryAsXesUseCase(execute, writer)

        useCase.export(ExportQueryAsXesRequest(query = "select e:name"), ByteArrayOutputStream())

        assertEquals("Query Result Log", writer.lastOptions!!.logName)
        assertEquals(false, writer.lastOptions!!.compress)
    }

    @Test
    fun `uses the real OpenXesWriter to produce valid XML`() {
        // Integration-ish: compose a real writer with a fake execute stage.
        val execute = StubExecute(sampleResult())
        val useCase = ExportQueryAsXesUseCase(execute, OpenXesWriter())

        val out = ByteArrayOutputStream()
        useCase.export(ExportQueryAsXesRequest(query = "select e:name"), out)
        val xml = out.toString(Charsets.UTF_8)

        assertTrue(xml.startsWith("<?xml"), "missing prolog: $xml")
        assertTrue(xml.contains("<log"), "missing <log>: $xml")
    }

    companion object {
        // No-op stand-ins — StubExecute overrides `execute`, so the underlying
        // dependencies are never touched. Kept here to keep StubExecute's ctor
        // happy without pulling Mockito in.
        private fun throwingParser() = object : com.processm.processminterpreter.application.ports.PqlParser {
            override fun parse(source: String) = error("parser should not be called from StubExecute")
        }
        private fun throwingExecutor() = object : com.processm.processminterpreter.application.ports.QueryPlanExecutor {
            override fun execute(
                plan: com.processm.processminterpreter.domain.pql.plan.LogicalPlan.Select,
                options: com.processm.processminterpreter.application.ports.ExecutionOptions,
            ) = error("executor should not be called from StubExecute")
            override fun findMatchingLogIds(
                plan: com.processm.processminterpreter.domain.pql.plan.CandidateLogPlan,
            ) = error("executor should not be called from StubExecute")
            override fun executeDelete(
                plan: com.processm.processminterpreter.domain.pql.plan.LogicalPlan.Delete,
            ) = error("executor should not be called from StubExecute")
        }
        private fun throwingRepo() = object : com.processm.processminterpreter.application.ports.LogRepository {
            override fun save(log: com.processm.processminterpreter.domain.log.Log) = log
            override fun findById(id: String): com.processm.processminterpreter.domain.log.Log? = null
            override fun findAll() = emptyList<com.processm.processminterpreter.domain.log.Log>()
            override fun search(namePart: String) = emptyList<com.processm.processminterpreter.domain.log.Log>()
            override fun findByAttribute(key: String, value: Any) =
                emptyList<com.processm.processminterpreter.domain.log.Log>()
            override fun findCreatedAfter(date: java.time.LocalDateTime) =
                emptyList<com.processm.processminterpreter.domain.log.Log>()
            override fun findCreatedBetween(
                start: java.time.LocalDateTime,
                end: java.time.LocalDateTime,
            ) = emptyList<com.processm.processminterpreter.domain.log.Log>()
            override fun getStatistics(id: String) = null
            override fun getStatisticsAll() =
                emptyList<Pair<com.processm.processminterpreter.domain.log.Log, com.processm.processminterpreter.application.ports.LogStatistics>>()
            override fun getClassifiers(id: String) = emptyMap<String, List<String>>()
            override fun update(log: com.processm.processminterpreter.domain.log.Log) = log
            override fun delete(id: String) = false
            override fun deleteWithData(id: String) = false
            override fun exists(id: String) = false
        }
        private fun throwingDataStores() =
            object : com.processm.processminterpreter.application.ports.DataStoreRepository {
                override fun save(dataStore: com.processm.processminterpreter.domain.datastore.DataStore) = dataStore
                override fun findById(id: String): com.processm.processminterpreter.domain.datastore.DataStore? = null
                override fun findAll() = emptyList<com.processm.processminterpreter.domain.datastore.DataStore>()
                override fun findLogSummaries(dataStoreId: String) =
                    emptyList<com.processm.processminterpreter.application.ports.DataStoreLogSummary>()
                override fun update(dataStore: com.processm.processminterpreter.domain.datastore.DataStore) = dataStore
                override fun deleteWithLogs(id: String) = false
                override fun exists(id: String) = false
                override fun attachLog(dataStoreId: String, logId: String) = Unit
            }
    }
}
