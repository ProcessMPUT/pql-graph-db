package com.processm.processminterpreter.benchmark

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

object CsvWriter {
    fun write(
        path: Path,
        headers: List<String>,
        rows: List<List<Any?>>,
    ) {
        path.parent.createDirectories()
        val text = buildString {
            appendLine(headers.joinToString(",") { escape(it) })
            rows.forEach { row ->
                appendLine(row.joinToString(",") { escape(it?.toString().orEmpty()) })
            }
        }
        val temporary = Files.createTempFile(path.parent, path.fileName.toString(), ".partial")
        try {
            temporary.writeText(text)
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            '"' + value.replace("\"", "\"\"") + '"'
        } else {
            value
        }
}
