package com.processm.processminterpreter.benchmark

import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import javax.xml.stream.XMLInputFactory
import kotlin.math.ceil
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
            DatasetType.REAL, DatasetType.FIXTURE -> inspectExisting(spec)
        }

    private fun generateSynthetic(
        spec: BenchmarkDatasetSpec,
        outputDirectory: Path,
    ): PreparedDataset {
        val traces = requireNotNull(spec.traces) { "Synthetic dataset ${spec.name} is missing traces" }
        val eventsPerTrace = requireNotNull(spec.eventsPerTrace) { "Synthetic dataset ${spec.name} is missing eventsPerTrace" }
        val attributesPerEvent =
            requireNotNull(spec.attributesPerEvent) { "Synthetic dataset ${spec.name} is missing attributesPerEvent" }
        val activityCount = spec.activityCount ?: minOf(20, eventsPerTrace)
        val variantCount = spec.variantCount ?: 1
        require(traces > 0) { "Synthetic dataset ${spec.name} must contain at least one trace" }
        require(eventsPerTrace > 0) { "Synthetic dataset ${spec.name} must contain events" }
        require(activityCount > 0) { "Synthetic dataset ${spec.name} must contain activities" }
        require(variantCount in 1..traces) {
            "Synthetic dataset ${spec.name} requests $variantCount variants for $traces traces"
        }
        if (spec.variantCount != null) {
            require(eventsPerTrace >= activityCount + VARIANT_ENCODING_EVENTS) {
                "Synthetic variant dataset ${spec.name} needs at least activityCount + $VARIANT_ENCODING_EVENTS events per trace"
            }
            require(variantCount.toLong() <= activityCount.toLong() * activityCount) {
                "Synthetic variant dataset ${spec.name} cannot encode $variantCount variants with $activityCount activities"
            }
        }

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
                    val activityIndex = activityIndex(spec, traceIndex, eventIndex, activityCount, variantCount)
                    val timestamp = Instant.parse("2020-01-01T00:00:00Z")
                        .plus((traceIndex.toLong() * eventsPerTrace) + eventIndex, ChronoUnit.MINUTES)
                    writer.appendLine("    <event>")
                    writer.appendLine("""      <string key="concept:name" value="activity-${activityIndex + 1}"/>""")
                    writer.appendLine("""      <date key="time:timestamp" value="$timestamp"/>""")
                    writer.appendLine("""      <float key="cost:total" value="${(eventIndex + 1) * 1.25}"/>""")
                    repeat(attributesPerEvent) { attrIndex ->
                        writer.appendLine(
                            """      <string key="attr_${attrIndex + 1}" value="v-${attrIndex + 1}"/>""",
                        )
                    }
                    writer.appendLine("    </event>")
                }
                writer.appendLine("  </trace>")
            }
            writer.appendLine("</log>")
        }

        val gzFile = outputDirectory.resolve("${spec.name}.xes.gz")
        BestCompressionGzipOutputStream(BufferedOutputStream(gzFile.outputStream())).use { gzip ->
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
            totalAttributes = SYNTHETIC_LOG_ATTRIBUTES +
                traces * (1 + eventsPerTrace * (attributesPerEvent + STANDARD_EVENT_ATTRIBUTES)),
            xesBytes = xesFile.fileSize(),
            xesGzBytes = gzFile.fileSize(),
            meanEventsPerTrace = eventsPerTrace.toDouble(),
            medianEventsPerTrace = eventsPerTrace.toDouble(),
            p95EventsPerTrace = eventsPerTrace,
            maxEventsPerTrace = eventsPerTrace,
            activityCount = if (spec.variantCount == null) minOf(activityCount, eventsPerTrace) else activityCount,
            variantCount = variantCount,
            fileSha256 = sha256(gzFile),
            meanEventAttributes = (attributesPerEvent + STANDARD_EVENT_ATTRIBUTES).toDouble(),
            collection = spec.collection,
            collectionOrder = spec.collectionOrder,
        )
    }

    private fun activityIndex(
        spec: BenchmarkDatasetSpec,
        traceIndex: Int,
        eventIndex: Int,
        activityCount: Int,
        variantCount: Int,
    ): Int {
        if (spec.variantCount == null) return eventIndex % activityCount
        val variantIndex = traceIndex % variantCount
        return when (eventIndex) {
            0 -> variantIndex % activityCount
            1 -> variantIndex / activityCount
            else -> (eventIndex - VARIANT_ENCODING_EVENTS) % activityCount
        }
    }

    private fun inspectExisting(spec: BenchmarkDatasetSpec): PreparedDataset {
        val path = Path.of(requireNotNull(spec.resourcePath) { "Dataset ${spec.name} is missing resourcePath" })
        require(Files.exists(path)) { "Missing benchmark dataset: $path" }
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
            meanEventsPerTrace = stats.meanEventsPerTrace,
            medianEventsPerTrace = stats.medianEventsPerTrace,
            p95EventsPerTrace = stats.p95EventsPerTrace,
            maxEventsPerTrace = stats.maxEventsPerTrace,
            activityCount = stats.activityCount,
            variantCount = stats.variantCount,
            sourceDoi = spec.sourceDoi,
            fileSha256 = sha256(path),
            meanEventAttributes = stats.meanEventAttributes,
            collection = spec.collection,
            collectionOrder = spec.collectionOrder,
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

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        private const val STANDARD_EVENT_ATTRIBUTES = 3
        private const val SYNTHETIC_LOG_ATTRIBUTES = 2
        private const val VARIANT_ENCODING_EVENTS = 2
    }
}

/**
 * Synthetic million-event inputs must stay inside the stock REFERENCE service's
 * 5 MiB compressed-stream limit. Compression changes bytes on the wire only;
 * both systems still receive the exact same uncompressed XES content.
 */
private class BestCompressionGzipOutputStream(output: java.io.OutputStream) : GZIPOutputStream(output) {
    init {
        def.setLevel(Deflater.BEST_COMPRESSION)
    }
}

data class XesDatasetStats(
    val traces: Int,
    val events: Int,
    val eventAttributes: Int,
    val totalAttributes: Int,
    val uncompressedBytes: Long,
    val meanEventsPerTrace: Double,
    val medianEventsPerTrace: Double,
    val p95EventsPerTrace: Int,
    val maxEventsPerTrace: Int,
    val activityCount: Int,
    val variantCount: Int,
    val meanEventAttributes: Double,
)

object XesDatasetInspector {
    fun inspect(path: Path): XesDatasetStats {
        var traces = 0
        var events = 0
        var totalAttributes = 0
        var eventAttributes = 0
        var insideEvent = false
        var depth = 0
        var eventDepth = -1
        var currentEventName: String? = null
        var currentTraceEventNames = mutableListOf<String?>()
        val traceLengths = mutableListOf<Int>()
        val activities = mutableSetOf<String>()
        val variants = mutableSetOf<String>()
        val digest = MessageDigest.getInstance("SHA-256")
        var uncompressedBytes = 0L

        XesStreams.openPossiblyCompressed(path).use { input ->
            val counting = input.buffered()
            val reader = XMLInputFactory.newFactory().createXMLStreamReader(counting)
            while (reader.hasNext()) {
                val event = reader.next()
                if (event == javax.xml.stream.XMLStreamConstants.START_ELEMENT) {
                    depth++
                    when (reader.localName) {
                        "trace" -> traces++
                        "event" -> {
                            events++
                            insideEvent = true
                            eventDepth = depth
                            currentEventName = null
                        }
                        "string", "date", "int", "float", "boolean", "id", "list", "container" -> {
                            if (reader.getAttributeValue(null, "key") != null) {
                                totalAttributes++
                                if (insideEvent) eventAttributes++
                            }
                            if (insideEvent && depth == eventDepth + 1 && reader.localName == "string" &&
                                reader.getAttributeValue(null, "key") == "concept:name"
                            ) {
                                currentEventName = reader.getAttributeValue(null, "value")
                                currentEventName?.let(activities::add)
                            }
                        }
                    }
                } else if (event == javax.xml.stream.XMLStreamConstants.END_ELEMENT) {
                    when (reader.localName) {
                        "event" -> {
                            currentTraceEventNames += currentEventName
                            insideEvent = false
                            eventDepth = -1
                        }
                        "trace" -> {
                            traceLengths += currentTraceEventNames.size
                            currentTraceEventNames.forEach { name ->
                                digest.update((name ?: MISSING_ACTIVITY).toByteArray(Charsets.UTF_8))
                                digest.update(0.toByte())
                            }
                            variants += Base64.getEncoder().encodeToString(digest.digest())
                            currentTraceEventNames = mutableListOf()
                        }
                    }
                    depth--
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

        val sortedLengths = traceLengths.sorted()
        return XesDatasetStats(
            traces = traces,
            events = events,
            eventAttributes = eventAttributes,
            totalAttributes = totalAttributes,
            uncompressedBytes = uncompressedBytes,
            meanEventsPerTrace = if (traces == 0) 0.0 else events.toDouble() / traces,
            medianEventsPerTrace = median(sortedLengths),
            p95EventsPerTrace = percentile95(sortedLengths),
            maxEventsPerTrace = sortedLengths.lastOrNull() ?: 0,
            activityCount = activities.size,
            variantCount = variants.size,
            meanEventAttributes = if (events == 0) 0.0 else eventAttributes.toDouble() / events,
        )
    }

    private fun median(sorted: List<Int>): Double = when {
        sorted.isEmpty() -> 0.0
        sorted.size % 2 == 1 -> sorted[sorted.size / 2].toDouble()
        else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
    }

    private fun percentile95(sorted: List<Int>): Int {
        if (sorted.isEmpty()) return 0
        val index = (ceil(sorted.size * 0.95).toInt() - 1).coerceIn(sorted.indices)
        return sorted[index]
    }

    private const val MISSING_ACTIVITY = "<missing concept:name>"
}
