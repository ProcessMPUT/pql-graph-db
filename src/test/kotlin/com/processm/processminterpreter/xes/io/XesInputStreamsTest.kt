package com.processm.processminterpreter.xes.io

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class XesInputStreamsTest {
    private val inputStreams = XesInputStreams()

    @Test
    fun `returns plain input stream unchanged when bytes are not gzipped`() {
        val content = "<log><trace/></log>".toByteArray(Charsets.UTF_8)

        val result = inputStreams.openPossiblyGzipped(ByteArrayInputStream(content)).use { it.readBytes() }

        assertArrayEquals(content, result)
    }

    @Test
    fun `unwraps gzipped input stream`() {
        val content = "<log><trace><event/></trace></log>".toByteArray(Charsets.UTF_8)

        val result = inputStreams.openPossiblyGzipped(ByteArrayInputStream(content.gzip())).use { it.readBytes() }

        assertArrayEquals(content, result)
    }

    @Test
    fun `opens classpath resource with optional leading slash`() {
        val stream = inputStreams.openClasspathResource("/logs/sample_process.xes")

        stream.use {
            assertNotNull(it.readBytes().firstOrNull())
        }
    }

    @Test
    fun `fails explicitly when classpath resource does not exist`() {
        assertThrows(IllegalArgumentException::class.java) {
            inputStreams.openClasspathResource("logs/missing.xes")
        }
    }

    private fun ByteArray.gzip(): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(this) }
        return output.toByteArray()
    }
}
