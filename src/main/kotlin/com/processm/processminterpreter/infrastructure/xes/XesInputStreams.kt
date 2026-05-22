package com.processm.processminterpreter.infrastructure.xes

import org.springframework.stereotype.Component
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

/**
 * Opens XES input sources without leaking transport details into the import flow.
 */
@Component
class XesInputStreams {
    fun openPossiblyGzipped(inputStream: InputStream): InputStream {
        val pushback = PushbackInputStream(inputStream, GZIP_MAGIC_SIZE)
        val header = ByteArray(GZIP_MAGIC_SIZE)
        val bytesRead = pushback.read(header)
        if (bytesRead > 0) {
            pushback.unread(header, 0, bytesRead)
        }

        val gzipped = bytesRead == GZIP_MAGIC_SIZE &&
            header[0] == GZIP_MAGIC_0.toByte() &&
            header[1] == GZIP_MAGIC_1.toByte()

        return if (gzipped) GZIPInputStream(pushback) else pushback
    }

    fun openClasspathResource(resourcePath: String): InputStream {
        val normalizedPath = resourcePath.removePrefix("/")
        return javaClass.classLoader.getResourceAsStream(normalizedPath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")
    }

    private companion object {
        const val GZIP_MAGIC_SIZE = 2
        const val GZIP_MAGIC_0 = 0x1f
        const val GZIP_MAGIC_1 = 0x8b
    }
}
