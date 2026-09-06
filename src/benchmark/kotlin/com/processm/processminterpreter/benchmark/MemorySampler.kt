package com.processm.processminterpreter.benchmark

import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Background memory sampler for Q3 (methodology 4/Q3 and 5.2/5.4).
 *
 * A daemon thread polls all configured [MemorySource]s once per [intervalMillis].
 * Samples are recorded during import-resource and query resource windows.
 * Between windows the sampler is quiescent; it never overlaps latency blocks.
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
        activeSystem: String = "",
    ) {
        probeLock.withLock {
            context = phase?.let { MemorySamplingContext(it, datasetName, operationLabel, activeSystem) }
        }
    }

    /**
     * Stops scheduling probes and waits until a probe already in progress has exited.
     * Merely clearing the phase is racy: the sampler may already have observed the old
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

    fun stop() {
        running = false
        thread?.interrupt()
        thread?.join(5_000)
        thread = null
    }

    fun samples(): List<MemorySample> = recorded.toList()

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
        val values = sources.flatMap { source -> runCatching { source.sample() }.getOrDefault(emptyList()) }
        val completedAt = Instant.now().toString()
        val withinWindow = probeLock.withLock { context === currentContext }
        values.forEach { (component, bytes) ->
                recorded += MemorySample(
                    timestamp = timestamp,
                    phase = currentContext.phase,
                    component = component,
                    bytes = bytes,
                    datasetName = currentContext.datasetName,
                    operationLabel = currentContext.operationLabel,
                    completedAt = completedAt,
                    activeSystem = currentContext.activeSystem,
                    withinWindow = withinWindow,
                )
        }
    }
}

private data class MemorySamplingContext(
    val phase: String,
    val datasetName: String,
    val operationLabel: String,
    val activeSystem: String = "",
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

/** Count simultaneous, complete samples of the active system inside one resource window. */
fun completeMemorySampleCount(
    samples: List<MemorySample>, dataset: String, query: String, system: String, components: Set<String>,
): Int = samples.filter {
    it.withinWindow && it.phase == "queries" && it.datasetName == dataset &&
        it.operationLabel == query && it.activeSystem == system && it.component in components
}.groupBy { it.timestamp }.count { (_, rows) ->
    rows.size == components.size && rows.map { it.component }.toSet() == components
}
