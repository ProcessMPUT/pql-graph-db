package com.processm.processminterpreter.benchmark

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Background memory sampler for Q3 (methodology 4/Q3 and 5.2/5.4).
 *
 * A daemon thread polls all configured [MemorySource]s once per [intervalMillis].
 * Samples are recorded only while a phase is active (`idle` baseline before imports,
 * `queries` during the query phase); between phases sampling is paused so imports and
 * report writing do not pollute the series.
 */
class MemorySampler(
    private val sources: List<MemorySource>,
    private val intervalMillis: Long = 1_000L,
) {
    fun interface MemorySource {
        /** Returns (component, bytes) pairs; empty on probe failure. */
        fun sample(): List<Pair<String, Long>>
    }

    private val recorded = ConcurrentLinkedQueue<MemorySample>()

    @Volatile
    private var phase: String? = null

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (thread != null) return
        running = true
        thread = Thread(::loop, "benchmark-memory-sampler").apply {
            isDaemon = true
            start()
        }
    }

    fun setPhase(phase: String?) {
        this.phase = phase
    }

    /** Samples in phase [phase] for [seconds], blocking the caller (idle baseline). */
    fun sampleBlocking(
        phase: String,
        seconds: Int,
    ) {
        setPhase(phase)
        try {
            Thread.sleep(seconds * 1_000L)
        } finally {
            setPhase(null)
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(5_000)
        thread = null
    }

    fun samples(): List<MemorySample> = recorded.toList()

    fun summaries(): List<MemorySummary> = summarizeMemory(samples())

    private fun loop() {
        while (running) {
            val currentPhase = phase
            if (currentPhase != null) {
                val timestamp = Instant.now().toString()
                sources.forEach { source ->
                    runCatching { source.sample() }.getOrDefault(emptyList()).forEach { (component, bytes) ->
                        recorded += MemorySample(
                            timestamp = timestamp,
                            phase = currentPhase,
                            component = component,
                            bytes = bytes,
                        )
                    }
                }
            }
            try {
                Thread.sleep(intervalMillis)
            } catch (_: InterruptedException) {
                return
            }
        }
    }
}

fun summarizeMemory(samples: List<MemorySample>): List<MemorySummary> =
    samples
        .groupBy { it.component to it.phase }
        .map { (key, rows) ->
            val sorted = rows.map { it.bytes }.sorted()
            MemorySummary(
                component = key.first,
                phase = key.second,
                medianBytes = sorted[(sorted.size - 1) / 2],
                peakBytes = sorted.last(),
            )
        }
        .sortedWith(compareBy({ it.component }, { it.phase }))

/**
 * Samples container memory via `docker stats --no-stream` for the given container names.
 * One invocation covers all containers (single docker round-trip per tick).
 */
class DockerStatsMemorySource(
    private val containers: Set<String>,
) : MemorySampler.MemorySource {
    override fun sample(): List<Pair<String, Long>> =
        runCatching {
            val process = ProcessBuilder(
                "docker", "stats", "--no-stream", "--format", "{{.Name}};{{.MemUsage}}",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) return@runCatching emptyList()
            output.lineSequence()
                .mapNotNull { parseDockerStatsLine(it) }
                .filter { it.first in containers }
                .toList()
        }.getOrDefault(emptyList())
}

/** Parses one `{{.Name}};{{.MemUsage}}` line, e.g. `processm-neo4j;1.234GiB / 7.653GiB`. */
fun parseDockerStatsLine(line: String): Pair<String, Long>? {
    val parts = line.trim().split(';')
    if (parts.size != 2) return null
    val usage = parts[1].substringBefore('/').trim()
    val bytes = parseByteSize(usage) ?: return null
    return parts[0].trim() to bytes
}

/** Parses docker-style sizes (`988.4MiB`, `1.5GiB`, `512kB`, `73B`) into bytes. */
fun parseByteSize(value: String): Long? {
    val match = Regex("""^([0-9]+(?:\.[0-9]+)?)\s*([A-Za-z]*)$""").find(value.trim()) ?: return null
    val amount = match.groupValues[1].toDoubleOrNull() ?: return null
    val multiplier = when (match.groupValues[2].lowercase()) {
        "", "b" -> 1.0
        "kb" -> 1_000.0
        "kib" -> 1024.0
        "mb" -> 1_000_000.0
        "mib" -> 1024.0 * 1024
        "gb" -> 1_000_000_000.0
        "gib" -> 1024.0 * 1024 * 1024
        "tb" -> 1_000_000_000_000.0
        "tib" -> 1024.0 * 1024 * 1024 * 1024
        else -> return null
    }
    return (amount * multiplier).toLong()
}

/**
 * Samples the RSS (working set) of one host process via Windows `tasklist`.
 * Used for the LOCAL application JVM which runs on the host, outside Docker.
 */
class WindowsProcessMemorySource(
    private val component: String,
    private val pid: Long,
) : MemorySampler.MemorySource {
    override fun sample(): List<Pair<String, Long>> =
        runCatching {
            val process = ProcessBuilder(
                "tasklist", "/FI", "PID eq $pid", "/FO", "CSV", "/NH",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(30, TimeUnit.SECONDS)) return@runCatching emptyList()
            val bytes = parseTasklistMemoryBytes(output) ?: return@runCatching emptyList()
            listOf(component to bytes)
        }.getOrDefault(emptyList())
}

/** Parses `tasklist /FO CSV /NH` output; the last CSV field is e.g. `"1,234,567 K"`. */
fun parseTasklistMemoryBytes(output: String): Long? {
    val line = output.lineSequence().firstOrNull { it.trim().startsWith("\"") } ?: return null
    val fields = line.trim().removePrefix("\"").removeSuffix("\"").split("\",\"")
    val memField = fields.lastOrNull() ?: return null
    // The thousands separator is locale-dependent (comma, dot, NO-BREAK SPACE on
    // Polish Windows) and additionally arrives charset-mangled when the console
    // codepage differs from the JVM charset — so keep digits only; the field is
    // always an integer kilobyte count.
    val kilobytes = memField
        .filter { it.isDigit() }
        .takeIf { it.isNotEmpty() }
        ?.toLong() ?: return null
    return kilobytes * 1024
}

/**
 * Resolves the PID of the local application JVM listening on [port].
 *
 * Primary source is `netstat -ano` (the LISTENING process is the JVM we want to
 * measure). The bootRun PID file written by `scripts/restart-app-8080.ps1`
 * (`build/bootrun-<port>.pid`) is only a fallback: it records the cmd.exe wrapper
 * that launched Gradle, not the application JVM itself.
 */
object LocalAppPidResolver {
    fun resolve(port: Int): Long? = netstatListeningPid(port) ?: pidFilePid(port)

    private fun netstatListeningPid(port: Int): Long? =
        runCatching {
            val process = ProcessBuilder("netstat", "-ano").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(30, TimeUnit.SECONDS)) return@runCatching null
            output.lineSequence()
                .map { it.trim().split(Regex("""\s+""")) }
                .firstOrNull { parts ->
                    parts.size >= 5 && parts[0] == "TCP" && parts[1].endsWith(":$port") && parts[3] == "LISTENING"
                }
                ?.last()
                ?.toLongOrNull()
        }.getOrNull()

    private fun pidFilePid(port: Int): Long? =
        runCatching {
            java.nio.file.Path.of("build", "bootrun-$port.pid")
                .takeIf { java.nio.file.Files.exists(it) }
                ?.let { java.nio.file.Files.readAllLines(it).firstOrNull()?.trim()?.toLongOrNull() }
        }.getOrNull()
}

const val MEMORY_PHASE_IDLE = "idle"
const val MEMORY_PHASE_QUERIES = "queries"
