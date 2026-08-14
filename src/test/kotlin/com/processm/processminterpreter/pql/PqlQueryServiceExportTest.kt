package com.processm.processminterpreter.pql

import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import com.processm.processminterpreter.xes.io.XesWriteOptions
import com.processm.processminterpreter.xes.io.OpenXesWriter
import com.processm.processminterpreter.neo4j.query.ExecutionOptions
import com.processm.processminterpreter.neo4j.query.QueryExecutionResult
import com.processm.processminterpreter.pql.parser.AntlrPqlParser
import com.processm.processminterpreter.pql.parser.AstBuilder
import com.processm.processminterpreter.pql.plan.LogicalPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class PqlQueryServiceExportTest {

    private class RecordingExecutor(
        private val result: QueryExecutionResult,
    ) : StubQueryPlanExecutor() {
        val calls = mutableListOf<Pair<LogicalPlan.Select, ExecutionOptions>>()

        override fun execute(plan: LogicalPlan.Select, options: ExecutionOptions): QueryExecutionResult {
            calls += plan to options
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

    private fun sampleResult(): QueryExecutionResult = QueryExecutionResult(
        logs = listOf(
            XesLog(
                conceptName = "x",
                traces = listOf(XesTrace(events = listOf(XesEvent(conceptName = "e")))),
            ),
        ),
        rows = emptyList(),
        rowCount = 1,
        executedQueryDescription = "MATCH ...",
    )

    private fun service(
        writer: OpenXesWriter,
        executor: RecordingExecutor = RecordingExecutor(sampleResult()),
    ): Pair<PqlQueryService, RecordingExecutor> =
        PqlQueryService(
            compiler = PqlCompiler(AntlrPqlParser(AstBuilder()), FakeLogRepository(), FakeDataStoreRepository()),
            executor = executor,
            writer = writer,
        ) to executor

    @Test
    fun `export runs the query and writes the XES to the provided stream`() {
        val writer = RecordingWriter()
        val (service, executor) = service(writer)

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
        assertEquals(1, executor.calls.size)
        assertEquals("log-1", executor.calls.single().first.source.logId)
        assertEquals("my-log", writer.lastOptions!!.logName)
        assertTrue(writer.lastOptions!!.compress)
    }

    @Test
    fun `DELETE queries are rejected before execution`() {
        val writer = RecordingWriter()
        val (service, executor) = service(writer)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.exportAsXes(ExportQueryAsXesRequest(query = " delete event "), ByteArrayOutputStream())
        }
        assertTrue(ex.message!!.contains("DELETE", ignoreCase = true))
        assertEquals(0, executor.calls.size, "executor should not run for DELETE queries")
        assertTrue(writer.lastLogs == null, "writer should not be touched")
    }

    @Test
    fun `executeRead refuses a DELETE query before it reaches the executor`() {
        val (service, executor) = service(RecordingWriter())

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.executeRead(ExecutePqlQueryRequest(query = "  delete where l:name = 'x'  "))
        }
        assertTrue(ex.message!!.contains("read/query endpoint"))
        assertEquals(0, executor.calls.size, "a read endpoint must not run the mutating execute path")
    }

    @Test
    fun `executeRead refuses DELETE hidden behind a leading comment`() {
        val (service, executor) = service(RecordingWriter())

        assertThrows(IllegalArgumentException::class.java) {
            service.executeRead(ExecutePqlQueryRequest(query = "/* harmless */ delete where l:name = 'x'"))
        }

        assertEquals(0, executor.calls.size, "a read endpoint must not execute a comment-prefixed DELETE")
    }

    @Test
    fun `executeRead delegates a SELECT query to execute`() {
        val (service, executor) = service(RecordingWriter())

        val result = service.executeRead(ExecutePqlQueryRequest(query = "select e:name"))

        assertEquals(1, executor.calls.size)
        assertEquals(1, result.logs.size)
    }

    @Test
    fun `default request options produce uncompressed XES with the default log name`() {
        val writer = RecordingWriter()
        val service = service(writer).first

        service.exportAsXes(ExportQueryAsXesRequest(query = "select e:name"), ByteArrayOutputStream())

        assertEquals("Query Result Log", writer.lastOptions!!.logName)
        assertEquals(false, writer.lastOptions!!.compress)
    }

    @Test
    fun `uses the real OpenXesWriter to produce valid XML`() {
        // Integration-ish: compose the real compiler and writer with a fake executor.
        val service = service(OpenXesWriter()).first

        val out = ByteArrayOutputStream()
        service.exportAsXes(ExportQueryAsXesRequest(query = "select e:name"), out)
        val xml = out.toString(Charsets.UTF_8)

        assertTrue(xml.startsWith("<?xml"), "missing prolog: $xml")
        assertTrue(xml.contains("<log"), "missing <log>: $xml")
    }
}
