package com.processm.processminterpreter.benchmark

import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.GZIPOutputStream
import javax.xml.stream.XMLInputFactory
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.inputStream
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.outputStream

class XesDatasetGenerator {
    fun prepare(
        spec: BenchmarkDatasetSpec,
        outputDirectory: Path,
    ): PreparedDataset =
        when (spec.type) {
            DatasetType.SYNTHETIC -> generateSynthetic(spec, outputDirectory)
            DatasetType.REAL -> inspectReal(spec)
        }

    private fun generateSynthetic(
        spec: BenchmarkDatasetSpec,
        outputDirectory: Path,
    ): PreparedDataset {
        val traces = requireNotNull(spec.traces) { "Synthetic dataset ${spec.name} is missing traces" }
        val eventsPerTrace = requireNotNull(spec.eventsPerTrace) { "Synthetic dataset ${spec.name} is missing eventsPerTrace" }
        val attributesPerEvent =
            requireNotNull(spec.attributesPerEvent) { "Synthetic dataset ${spec.name} is missing attributesPerEvent" }

        outputDirectory.createDirectories()
        val xesFile = outputDirectory.resolve("${spec.name}.xes")
        Files.newBufferedWriter(xesFile).use { writer ->
            writer.appendLine("""<?xml version="1.0" encoding="UTF-8" ?>""")
            writer.appendLine("""<log xes.version="1.0" xes.features="nested-attributes" openxes.version="1.0RC7" xmlns="http://www.xes-standard.org/">""")
            writer.appendLine("""  <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>""")
            writer.appendLine("""  <extension name="Time" prefix="time" uri="http://www.xes-standard.org/time.xesext"/>""")
            writer.appendLine("""  <classifier name="Activity" keys="concept:name"/>""")
            writer.appendLine("""  <string key="concept:name" value="${spec.name}"/>""")
            writer.appendLine("""  <string key="source" value="synthetic-benchmark"/>""")
            repeat(traces) { traceIndex ->
                writer.appendLine("  <trace>")
                writer.appendLine("""    <string key="concept:name" value="trace-${traceIndex + 1}"/>""")
                repeat(eventsPerTrace) { eventIndex ->
                    val timestamp = Instant.parse("2020-01-01T00:00:00Z")
                        .plus((traceIndex.toLong() * eventsPerTrace) + eventIndex, ChronoUnit.MINUTES)
                    writer.appendLine("    <event>")
                    writer.appendLine("""      <string key="concept:name" value="activity-${(eventIndex % 20) + 1}"/>""")
                    writer.appendLine("""      <date key="time:timestamp" value="$timestamp"/>""")
                    writer.appendLine("""      <float key="cost:total" value="${(eventIndex + 1) * 1.25}"/>""")
                    repeat(attributesPerEvent) { attrIndex ->
                        writer.appendLine(
                            """      <string key="attr_${attrIndex + 1}" value="v-${traceIndex % 50}-${eventIndex % 25}-${attrIndex + 1}"/>""",
                        )
                    }
                    writer.appendLine("    </event>")
                }
                writer.appendLine("  </trace>")
            }
            writer.appendLine("</log>")
        }

        val gzFile = outputDirectory.resolve("${spec.name}.xes.gz")
        GZIPOutputStream(BufferedOutputStream(gzFile.outputStream())).use { gzip ->
            xesFile.inputStream().use { input -> input.copyTo(gzip) }
        }

        return PreparedDataset(
            name = spec.name,
            series = spec.series,
            file = gzFile,
            traces = traces,
            eventsPerTrace = eventsPerTrace,
            totalEvents = traces * eventsPerTrace,
            attributesPerEvent = attributesPerEvent,
            totalAttributes = traces * (1 + eventsPerTrace * (attributesPerEvent + STANDARD_EVENT_ATTRIBUTES)),
            xesBytes = xesFile.fileSize(),
            xesGzBytes = gzFile.fileSize(),
        )
    }

    private fun inspectReal(spec: BenchmarkDatasetSpec): PreparedDataset {
        val path = Path.of(requireNotNull(spec.resourcePath) { "Real dataset ${spec.name} is missing resourcePath" })
        require(Files.exists(path)) { "Missing real benchmark dataset: $path" }
        val stats = XesDatasetInspector.inspect(path)
        return PreparedDataset(
            name = spec.name,
            series = spec.series,
            file = path,
            traces = stats.traces,
            eventsPerTrace = if (stats.traces == 0) 0 else stats.events / stats.traces,
            totalEvents = stats.events,
            attributesPerEvent = if (stats.events == 0) 0 else stats.eventAttributes / stats.events,
            totalAttributes = stats.totalAttributes,
            xesBytes = stats.uncompressedBytes,
            xesGzBytes = if (path.extension.equals("gz", ignoreCase = true)) path.fileSize() else gzipSize(path),
        )
    }

    private fun gzipSize(path: Path): Long {
        val tmp = Files.createTempFile(path.nameWithoutExtension, ".xes.gz")
        try {
            GZIPOutputStream(BufferedOutputStream(tmp.outputStream())).use { gzip ->
                path.inputStream().use { input -> input.copyTo(gzip) }
            }
            return tmp.fileSize()
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private companion object {
        private const val STANDARD_EVENT_ATTRIBUTES = 3
    }
}

data class XesDatasetStats(
    val traces: Int,
    val events: Int,
    val eventAttributes: Int,
    val totalAttributes: Int,
    val uncompressedBytes: Long,
)

object XesDatasetInspector {
    fun inspect(path: Path): XesDatasetStats {
        var traces = 0
        var events = 0
        var totalAttributes = 0
        var eventAttributes = 0
        var insideEvent = false
        var uncompressedBytes = 0L

        XesStreams.openPossiblyCompressed(path).use { input ->
            val counting = input.buffered()
            val reader = XMLInputFactory.newFactory().createXMLStreamReader(counting)
            while (reader.hasNext()) {
                val event = reader.next()
                if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                    when (reader.localName) {
                        "trace" -> traces++
                        "event" -> {
                            events++
                            insideEvent = true
                        }
                        "string", "date", "int", "float", "boolean", "id", "list", "container" -> {
                            if (reader.getAttributeValue(null, "key") != null) {
                                totalAttributes++
                                if (insideEvent) eventAttributes++
                            }
                        }
                    }
                } else if (event == javax.xml.stream.XMLStreamConstants.END_ELEMENT) {
                    when (reader.localName) {
                        "event" -> insideEvent = false
                    }
                }
            }
        }

        XesStreams.openPossiblyCompressed(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                uncompressedBytes += read
            }
        }

        return XesDatasetStats(
            traces = traces,
            events = events,
            eventAttributes = eventAttributes,
            totalAttributes = totalAttributes,
            uncompressedBytes = uncompressedBytes,
        )
    }
}
