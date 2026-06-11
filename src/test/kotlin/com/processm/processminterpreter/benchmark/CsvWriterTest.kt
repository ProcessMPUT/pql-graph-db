package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CsvWriterTest {
    @Test
    fun `escape quotes commas and new lines`() {
        assertEquals("plain", CsvWriter.escape("plain"))
        assertEquals("\"a,b\"", CsvWriter.escape("a,b"))
        assertEquals("\"a\"\"b\"", CsvWriter.escape("a\"b"))
        assertEquals("\"a\nb\"", CsvWriter.escape("a\nb"))
    }
}
