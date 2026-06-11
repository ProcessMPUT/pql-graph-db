package com.processm.processminterpreter.benchmark

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlin.io.path.inputStream

object XesStreams {
    fun openPossiblyCompressed(path: Path): InputStream {
        val input = path.inputStream().buffered()
        input.mark(4)
        val magic1 = input.read()
        val magic2 = input.read()
        input.reset()
        return if (magic1 == 0x1f && magic2 == 0x8b) GZIPInputStream(input) else input
    }

    fun firstZipEntry(bytes: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val entry = zip.nextEntry ?: return bytes
            if (entry.isDirectory) return bytes
            return zip.readBytes()
        }
    }
}
