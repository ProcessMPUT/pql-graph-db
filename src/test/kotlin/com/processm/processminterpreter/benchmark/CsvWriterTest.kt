package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readLines

class CsvWriterTest {
    @Test
    fun `escape quotes commas and new lines`() {
        assertEquals("plain", CsvWriter.escape("plain"))
        assertEquals("\"a,b\"", CsvWriter.escape("a,b"))
        assertEquals("\"a\"\"b\"", CsvWriter.escape("a\"b"))
        assertEquals("\"a\nb\"", CsvWriter.escape("a\nb"))
    }

    @Test
    fun `writes header and rows including extended query result columns`(
        @TempDir tempDir: Path,
    ) {
        val file = tempDir.resolve("query-results.csv")
        CsvWriter.write(
            file,
            listOf("system", "run", "phase", "logCount", "traceCount", "eventCount", "details"),
            listOf(
                listOf("local", 0, QUERY_PHASE_COLD, 1, 10, 100, ""),
                listOf("reference", 1, QUERY_PHASE_WARM, 1, 10, 100, null),
            ),
        )
        val lines = file.readLines()
        assertEquals("system,run,phase,logCount,traceCount,eventCount,details", lines[0])
        assertEquals("local,0,cold,1,10,100,", lines[1])
        assertEquals("reference,1,warm,1,10,100,", lines[2])
    }
}
