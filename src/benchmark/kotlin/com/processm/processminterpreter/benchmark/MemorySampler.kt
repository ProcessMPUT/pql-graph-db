package com.processm.processminterpreter.benchmark

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToLong

/**
 * Background memory sampler for Q3 (methodology 4/Q3 and 5.2/5.4).
 *
 * A daemon thread polls all configured [MemorySource]s once per [intervalMillis].
 * Samples are recorded only while a phase is active (`idle` baseline before imports,
 * `queries` during an unmeasured resource replay); between phases sampling is paused so
 * timed latency requests, imports and report writing do not overlap Docker polling.
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
    private val probeLock = ReentrantLock()
    private val probeIdle = probeLock.newCondition()

    @Volatile
    private var context: MemorySamplingContext? = null
    private var probing = false

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

    fun setPhase(
        phase: String?,
        datasetName: String = "",
        operationLabel: String = "",
    ) {
        probeLock.withLock {
            context = phase?.let { MemorySamplingContext(it, datasetName, operationLabel) }
        }
    }

    /**
     * Stops scheduling probes and waits until a probe already in progress has exited.
     * Merely clearing [phase] is racy: the sampler may already have observed the old
     * phase and be starting `docker stats` while the caller begins a timed request.
     */
    fun pauseAndAwaitQuiescence(timeoutMillis: Long = 30_000L) {
        probeLock.withLock {
            context = null
            var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            while (probing) {
                check(remainingNanos > 0L) { "Memory sampler did not become quiescent within ${timeoutMillis}ms" }
                remainingNanos = probeIdle.awaitNanos(remainingNanos)
            }
        }
    }

    /** Guarantees at least one observation for a short resource block. */
    fun sampleOnce(
        phase: String,
        datasetName: String = "",
        operationLabel: String = "",
    ) {
        pauseAndAwaitQuiescence()
        recordProbe(MemorySamplingContext(phase, datasetName, operationLabel))
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
            pauseAndAwaitQuiescence()
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
        var nextProbeNanos = System.nanoTime()
        while (running) {
            val currentContext = probeLock.withLock {
                context?.also { probing = true }
            }
            if (currentContext != null) {
                try {
                    recordProbe(currentContext)
                } finally {
                    probeLock.withLock {
                        probing = false
                        probeIdle.signalAll()
                    }
                }
            }
            nextProbeNanos += intervalMillis * 1_000_000L
            val remainingNanos = nextProbeNanos - System.nanoTime()
            if (remainingNanos <= 0L) {
                // `docker stats --no-stream` can itself take longer than the target
                // interval. Do not add another full sleep on top of that latency.
                nextProbeNanos = System.nanoTime()
                continue
            }
            try {
                val millis = remainingNanos / 1_000_000L
                val nanos = (remainingNanos % 1_000_000L).toInt()
                Thread.sleep(millis, nanos)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun recordProbe(currentContext: MemorySamplingContext) {
        val timestamp = Instant.now().toString()
        sources.forEach { source ->
            runCatching { source.sample() }.getOrDefault(emptyList()).forEach { (component, bytes) ->
                recorded += MemorySample(
                    timestamp = timestamp,
                    phase = currentContext.phase,
                    component = component,
                    bytes = bytes,
                    datasetName = currentContext.datasetName,
                    operationLabel = currentContext.operationLabel,
                )
            }
        }
    }
}

fun summarizeMemory(samples: List<MemorySample>): List<MemorySummary> {
    fun summarize(rows: List<MemorySample>): List<MemorySummary> = rows
        .groupBy { listOf(it.component, it.phase, it.datasetName, it.operationLabel) }
        .map { (key, rows) ->
            val sorted = rows.map { it.bytes }.sorted()
            MemorySummary(
                component = key[0],
                phase = key[1],
                medianBytes = ThesisStatistics.quantile(sorted.map(Long::toDouble), 0.50).roundToLong(),
                peakBytes = sorted.last(),
                datasetName = key[2],
                operationLabel = key[3],
            )
        }

    val totals = samples
        .groupBy { listOf(it.timestamp, it.phase, it.datasetName, it.operationLabel) }
        .flatMap { (key, rows) ->
            val values = rows.associate { it.component to it.bytes }
            buildList {
                val interpreter = values["processm-interpreter"]
                val neo4j = values["processm-neo4j"]
                if (interpreter != null && neo4j != null) {
                    add(MemorySample(key[0], key[1], "local-total", interpreter + neo4j, key[2], key[3]))
                }
                values["processm-server"]?.let {
                    add(MemorySample(key[0], key[1], "reference-total", it, key[2], key[3]))
                }
            }
        }
    return (summarize(samples) + summarize(totals))
        .sortedWith(compareBy({ it.datasetName }, { it.operationLabel }, { it.component }, { it.phase }))
}

private data class MemorySamplingContext(
    val phase: String,
    val datasetName: String,
    val operationLabel: String,
)

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

internal val isWindowsHost: Boolean
    get() = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/**
 * Samples the RSS (working set) of one host process, used for the LOCAL application
 * JVM which runs on the host, outside Docker.
 *
 * Cross-platform on purpose: `tasklist` on Windows, `ps -o rss=` on macOS/Linux.
 * Without the POSIX branch the source silently produced nothing there, so the LOCAL
 * side of the Q3 memory comparison counted only the Neo4j container and understated
 * this system's footprint — a fairness defect, not a cosmetic one.
 */
class ProcessMemorySource(
    private val component: String,
    private val pid: Long,
) : MemorySampler.MemorySource {
    override fun sample(): List<Pair<String, Long>> =
        runCatching {
            val command = if (isWindowsHost) {
                listOf("tasklist", "/FI", "PID eq $pid", "/FO", "CSV", "/NH")
            } else {
                listOf("ps", "-o", "rss=", "-p", pid.toString())
            }
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(30, TimeUnit.SECONDS)) return@runCatching emptyList()
            val bytes = if (isWindowsHost) {
                parseTasklistMemoryBytes(output)
            } else {
                parsePsRssBytes(output)
            } ?: return@runCatching emptyList()
            listOf(component to bytes)
        }.getOrDefault(emptyList())
}

/** Parses `ps -o rss= -p <pid>` output: a single integer in kilobytes. */
fun parsePsRssBytes(output: String): Long? =
    output.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
        ?.toLongOrNull()
        ?.times(1024)

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
 * The listening process is the JVM we want to measure: `netstat -ano` on Windows,
 * `lsof` on macOS/Linux (POSIX `netstat` prints no PID column, so the Windows parse
 * silently found nothing there). The bootRun PID file written by
 * `scripts/restart-app-8080.py` (`build/bootrun-<port>.pid`) is only a fallback — it
 * records the launcher process, which need not be the application JVM itself.
 */
object LocalAppPidResolver {
    fun resolve(port: Int): Long? = listeningPid(port) ?: pidFilePid(port)

    private fun listeningPid(port: Int): Long? =
        if (isWindowsHost) netstatListeningPid(port) else lsofListeningPid(port)

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

    private fun lsofListeningPid(port: Int): Long? =
        runCatching {
            val process = ProcessBuilder(
                "lsof", "-nP", "-iTCP:$port", "-sTCP:LISTEN", "-t",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(30, TimeUnit.SECONDS)) return@runCatching null
            output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && it.all(Char::isDigit) }
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
