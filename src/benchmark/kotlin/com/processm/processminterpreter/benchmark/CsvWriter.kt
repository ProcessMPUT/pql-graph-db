package com.processm.processminterpreter.benchmark

import java.nio.file.Path
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
        path.writeText(text)
    }

    fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            '"' + value.replace("\"", "\"\"") + '"'
        } else {
            value
        }
}
