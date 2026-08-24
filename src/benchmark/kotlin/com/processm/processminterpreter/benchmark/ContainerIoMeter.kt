package com.processm.processminterpreter.benchmark

import java.util.concurrent.TimeUnit

data class ContainerIoSnapshot(
    val counters: Map<String, ContainerIoCounters>,
    val details: String = "",
)

data class ContainerIoCounters(
    val blockReadBytes: Long?,
    val blockWriteBytes: Long?,
    val blockReadOperations: Long?,
    val blockWriteOperations: Long?,
    val networkReceiveBytes: Long?,
    val networkTransmitBytes: Long?,
)

/** Reads cumulative Docker/cgroup counters; callers take snapshots outside timed work. */
class ContainerIoMeter(
    /** container name -> benchmark system (`local` or `reference`). */
    private val containers: Map<String, String>,
) {
    fun snapshot(): ContainerIoSnapshot {
        if (containers.isEmpty()) return ContainerIoSnapshot(emptyMap(), "no containers configured")
        val stats = dockerStats()
        val errors = mutableListOf<String>()
        if (stats.isEmpty()) errors += "docker stats returned no matching counters"
        val counters = containers.keys.associateWith { container ->
            val byteCounters = stats[container]
            val cgroupCounters = cgroupIo(container)
            if (byteCounters == null) errors += "$container: byte counters unavailable"
            if (cgroupCounters == null) errors += "$container: cgroup io.stat unavailable"
            ContainerIoCounters(
                // io.stat is exact; Docker's rendered BlockIO value is rounded and
                // therefore only a fallback for hosts without cgroup v2 access.
                blockReadBytes = cgroupCounters?.readBytes ?: byteCounters?.blockReadBytes,
                blockWriteBytes = cgroupCounters?.writeBytes ?: byteCounters?.blockWriteBytes,
                blockReadOperations = cgroupCounters?.readOperations,
                blockWriteOperations = cgroupCounters?.writeOperations,
                networkReceiveBytes = byteCounters?.networkReceiveBytes,
                networkTransmitBytes = byteCounters?.networkTransmitBytes,
            )
        }
        return ContainerIoSnapshot(counters, errors.distinct().joinToString("; "))
    }

    fun deltas(
        phase: String,
        datasetName: String,
        operationLabel: String,
        run: Int,
        before: ContainerIoSnapshot,
        after: ContainerIoSnapshot,
    ): List<ContainerIoBenchmarkResult> = containers.map { (container, system) ->
        val start = before.counters[container]
        val end = after.counters[container]
        val raw = if (start != null && end != null) {
            listOf(
                delta(start.blockReadBytes, end.blockReadBytes),
                delta(start.blockWriteBytes, end.blockWriteBytes),
                delta(start.blockReadOperations, end.blockReadOperations),
                delta(start.blockWriteOperations, end.blockWriteOperations),
                delta(start.networkReceiveBytes, end.networkReceiveBytes),
                delta(start.networkTransmitBytes, end.networkTransmitBytes),
            )
        } else {
            List(6) { null }
        }
        val reset = start != null && end != null && listOf(
            start.blockReadBytes to end.blockReadBytes,
            start.blockWriteBytes to end.blockWriteBytes,
            start.blockReadOperations to end.blockReadOperations,
            start.blockWriteOperations to end.blockWriteOperations,
            start.networkReceiveBytes to end.networkReceiveBytes,
            start.networkTransmitBytes to end.networkTransmitBytes,
        ).any { (a, b) -> a != null && b != null && b < a }
        val requiredBytesAvailable = listOf(raw[0], raw[1], raw[4], raw[5]).all { it != null }
        val operationsAvailable = raw[2] != null && raw[3] != null
        val status = when {
            reset -> ContainerIoValidity.STATUS_COUNTER_RESET
            !requiredBytesAvailable -> ContainerIoValidity.STATUS_UNAVAILABLE
            !operationsAvailable -> ContainerIoValidity.STATUS_PARTIAL
            else -> ContainerIoValidity.STATUS_OK
        }
        ContainerIoBenchmarkResult(
            system = system,
            phase = phase,
            datasetName = datasetName,
            operationLabel = operationLabel,
            run = run,
            component = container,
            blockReadBytes = raw[0],
            blockWriteBytes = raw[1],
            blockReadOperations = raw[2],
            blockWriteOperations = raw[3],
            networkReceiveBytes = raw[4],
            networkTransmitBytes = raw[5],
            status = status,
            details = listOf(before.details, after.details).filter { it.isNotBlank() }.distinct().joinToString("; "),
        )
    }

    private fun dockerStats(): Map<String, ContainerIoCounters> = retryCompleteSnapshot(
        expectedKeys = containers.keys,
        attempts = DOCKER_STATS_ATTEMPTS,
        pause = { Thread.sleep(DOCKER_STATS_RETRY_MILLIS) },
    ) {
        dockerStatsOnce()
    }

    private fun dockerStatsOnce(): Map<String, ContainerIoCounters> = runCatching {
        val command = mutableListOf(
            "docker", "stats", "--no-stream", "--format", "{{.Name}};{{.BlockIO}};{{.NetIO}}",
        ).apply { addAll(containers.keys) }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) return@runCatching emptyMap()
        output.lineSequence().mapNotNull(::parseContainerIoStatsLine).toMap()
    }.getOrDefault(emptyMap())

    private fun cgroupIo(container: String): CgroupIoCounters? = runCatching {
        val process = ProcessBuilder("docker", "exec", container, "cat", "/sys/fs/cgroup/io.stat")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) return@runCatching null
        parseCgroupIoCounters(output)
    }.getOrNull()

    private fun delta(before: Long?, after: Long?): Long? =
        if (before != null && after != null && after >= before) after - before else null
}

private const val DOCKER_STATS_ATTEMPTS = 3
private const val DOCKER_STATS_RETRY_MILLIS = 250L

/** Retry a transiently incomplete multi-container snapshot outside timed work. */
fun <T> retryCompleteSnapshot(
    expectedKeys: Set<String>,
    attempts: Int,
    pause: () -> Unit,
    read: () -> Map<String, T>,
): Map<String, T> {
    require(attempts > 0) { "attempts must be positive" }
    var latest = emptyMap<String, T>()
    repeat(attempts) { attempt ->
        latest = read()
        if (expectedKeys.all(latest::containsKey)) return latest
        if (attempt + 1 < attempts) pause()
    }
    return latest
}

/** Parses one Docker stats line such as `app;1.2MB / 3kB;800kB / 2MB`. */
fun parseContainerIoStatsLine(line: String): Pair<String, ContainerIoCounters>? {
    val fields = line.trim().split(';')
    if (fields.size != 3) return null
    val block = parseDockerCounterPair(fields[1]) ?: return null
    val network = parseDockerCounterPair(fields[2]) ?: return null
    return fields[0].trim() to ContainerIoCounters(
        blockReadBytes = block.first,
        blockWriteBytes = block.second,
        blockReadOperations = null,
        blockWriteOperations = null,
        networkReceiveBytes = network.first,
        networkTransmitBytes = network.second,
    )
}

fun parseDockerCounterPair(value: String): Pair<Long, Long>? {
    val parts = value.split('/')
    if (parts.size != 2) return null
    return (parseByteSize(parts[0].trim()) ?: return null) to
        (parseByteSize(parts[1].trim()) ?: return null)
}

data class CgroupIoCounters(
    val readBytes: Long,
    val writeBytes: Long,
    val readOperations: Long,
    val writeOperations: Long,
)

/** Sums byte and operation counters over all devices in cgroup v2 `io.stat`. */
fun parseCgroupIoCounters(output: String): CgroupIoCounters? {
    var readBytes = 0L
    var writeBytes = 0L
    var reads = 0L
    var writes = 0L
    var found = false
    output.lineSequence().forEach { line ->
        val values = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { token ->
            val parts = token.split('=', limit = 2)
            if (parts.size == 2) parts[0] to parts[1].toLongOrNull() else null
        }.toMap()
        val rios = values["rios"]
        val wios = values["wios"]
        val rbytes = values["rbytes"]
        val wbytes = values["wbytes"]
        if (rios != null || wios != null || rbytes != null || wbytes != null) {
            found = true
            readBytes += rbytes ?: 0L
            writeBytes += wbytes ?: 0L
            reads += rios ?: 0L
            writes += wios ?: 0L
        }
    }
    return if (found) CgroupIoCounters(readBytes, writeBytes, reads, writes) else null
}
