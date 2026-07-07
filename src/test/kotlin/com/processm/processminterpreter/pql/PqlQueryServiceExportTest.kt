package com.processm.processminterpreter.pql

import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import com.processm.processminterpreter.xes.io.XesWriteOptions
import com.processm.processminterpreter.xes.io.OpenXesWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class PqlQueryServiceExportTest {

    // Test double that records what it was asked to write — avoids spinning up
    // the full compiler pipeline just to check orchestration. Overrides `execute`
    // on the service itself: `exportAsXes` must route through `this.execute(...)`,
    // so the stubbed execute is what the export path sees.
    private class StubExecute(private val result: QueryResult, writer: OpenXesWriter) : PqlQueryService(
        compiler = PqlCompiler(throwingParser(), FakeLogRepository(), FakeDataStoreRepository()),
        executor = throwingExecutor(),
        writer = writer,
    ) {
        val calls = mutableListOf<ExecutePqlQueryRequest>()
        override fun execute(request: ExecutePqlQueryRequest): QueryResult {
            calls += request
            return result
        }
    }

    private class RecordingWriter : OpenXesWriter() {
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
        val writer = RecordingWriter()
        val service = StubExecute(sampleResult(), writer)

        val out = ByteArrayOutputStream()
        val result = service.exportAsXes(
            ExportQueryAsXesRequest(query = "select e:name", logId = "log-1", compress = true, logName = "my-log"),
            out,
        )

        assertEquals(1, result.logCount)
        assertEquals(1, result.rowCount)
        assertTrue(result.executedQueryDescription.contains("MATCH"))
        assertEquals("<xes/>", out.toString(Charsets.UTF_8))

        // Forwarded arguments end-to-end:
        assertEquals(1, service.calls.size)
        assertEquals("log-1", service.calls.single().logId)
        assertEquals("my-log", writer.lastOptions!!.logName)
        assertTrue(writer.lastOptions!!.compress)
    }

    @Test
    fun `DELETE queries are rejected before execution`() {
        val writer = RecordingWriter()
        val service = StubExecute(sampleResult(), writer)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.exportAsXes(ExportQueryAsXesRequest(query = " DELETE FROM event "), ByteArrayOutputStream())
        }
        assertTrue(ex.message!!.contains("DELETE", ignoreCase = true))
        assertEquals(0, service.calls.size, "executor should not run for DELETE queries")
        assertTrue(writer.lastLogs == null, "writer should not be touched")
    }

    @Test
    fun `executeRead refuses a DELETE query before it reaches the executor`() {
        val service = StubExecute(sampleResult(), RecordingWriter())

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.executeRead(ExecutePqlQueryRequest(query = "  delete where l:name = 'x'  "))
        }
        assertTrue(ex.message!!.contains("read/query endpoint"))
        assertEquals(0, service.calls.size, "a read endpoint must not run the mutating execute path")
    }

    @Test
    fun `executeRead delegates a SELECT query to execute`() {
        val service = StubExecute(sampleResult(), RecordingWriter())

        val result = service.executeRead(ExecutePqlQueryRequest(query = "select e:name"))

        assertEquals(1, service.calls.size)
        assertEquals(1, result.logs.size)
    }

    @Test
    fun `default request options produce uncompressed XES with the default log name`() {
        val writer = RecordingWriter()
        val service = StubExecute(sampleResult(), writer)

        service.exportAsXes(ExportQueryAsXesRequest(query = "select e:name"), ByteArrayOutputStream())

        assertEquals("Query Result Log", writer.lastOptions!!.logName)
        assertEquals(false, writer.lastOptions!!.compress)
    }

    @Test
    fun `uses the real OpenXesWriter to produce valid XML`() {
        // Integration-ish: compose a real writer with a fake execute stage.
        val service = StubExecute(sampleResult(), OpenXesWriter())

        val out = ByteArrayOutputStream()
        service.exportAsXes(ExportQueryAsXesRequest(query = "select e:name"), out)
        val xml = out.toString(Charsets.UTF_8)

        assertTrue(xml.startsWith("<?xml"), "missing prolog: $xml")
        assertTrue(xml.contains("<log"), "missing <log>: $xml")
    }

    companion object {
        // Stand-ins — StubExecute overrides `execute`, so the underlying
        // dependencies are never touched. The repositories come from the shared
        // QueryTestFakes.kt; the parser and executor deliberately THROW on use so
        // a regression that routes export around the overridden `execute` fails loudly.
        private fun throwingParser() = object : com.processm.processminterpreter.pql.parser.AntlrPqlParser() {
            override fun parse(source: String) = error("parser should not be called from StubExecute")
        }
        private fun throwingExecutor() =
            object : StubQueryPlanExecutor() {
                override fun execute(
                    plan: com.processm.processminterpreter.pql.plan.LogicalPlan.Select,
                    options: com.processm.processminterpreter.neo4j.query.ExecutionOptions,
                ) = error("executor should not be called from StubExecute")
                override fun findMatchingLogIds(
                    plan: com.processm.processminterpreter.pql.plan.CandidateLogPlan,
                ) = error("executor should not be called from StubExecute")
                override fun executeDelete(
                    plan: com.processm.processminterpreter.pql.plan.LogicalPlan.Delete,
                ) = error("executor should not be called from StubExecute")
            }
    }
}
