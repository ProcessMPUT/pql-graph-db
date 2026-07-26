package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.lang.management.ManagementFactory

/**
 * Collects the environment facts METODOLOGIA §2.4/§5.1 promises in every run's
 * `environment.json`: host hardware plus, per measured container, the Docker
 * resource limits and the database memory configuration exposed through the
 * container environment. Best-effort — a failed probe records `unavailable`
 * instead of failing the benchmark run.
 */
object EnvironmentProbe {
    private val mapper = jacksonObjectMapper()

    fun collect(containers: Collection<String>): Map<String, Any?> =
        mapOf(
            "host" to hostInfo(),
            "containers" to containers.distinct().associateWith { containerInfo(it) },
        )

    private fun hostInfo(): Map<String, Any?> =
        mapOf(
            "cpuModel" to (System.getenv("PROCESSOR_IDENTIFIER") ?: "unavailable"),
            "logicalProcessors" to Runtime.getRuntime().availableProcessors(),
            "totalPhysicalMemoryBytes" to totalPhysicalMemoryBytes(),
        )

    private fun totalPhysicalMemoryBytes(): Any =
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.totalMemorySize
            ?: "unavailable"

    private fun containerInfo(name: String): Map<String, Any?> =
        dockerInspect(name)
            ?.let(::parseContainerInfo)
            ?.plus("effectiveJvmHeap" to (effectiveJvmHeap(name) ?: "unavailable"))
            ?: mapOf("status" to "unavailable")

    /**
     * The heap the JVM actually runs with, read from the container's main process.
     *
     * Both systems size their heap at startup rather than declaring it statically —
     * the reference computes it from the memory available to its container, and this
     * interpreter follows the same rule — so the configured environment alone does not
     * document what was measured. Best-effort: containers without a JVM report null.
     */
    private fun effectiveJvmHeap(name: String): String? =
        runCatching {
            // Scan every process, not just PID 1: the reference starts its JVM from a
            // shell script, so there the heap flags live on a child process.
            val process = ProcessBuilder(
                "docker", "exec", name, "sh", "-c", PROC_CMDLINE_SCAN,
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8)
            if (process.waitFor() != 0) return@runCatching null
            HEAP_FLAG.findAll(output)
                .map { it.value }
                .distinct()
                .sorted()
                .toList()
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" ")
        }.getOrNull()

    /** Parses one `docker inspect <name>` JSON document (visible for tests). */
    fun parseContainerInfo(inspectJson: String): Map<String, Any?> {
        val node = runCatching { mapper.readTree(inspectJson) }.getOrNull()
            ?.let { if (it.isArray) it.firstOrNull() else it }
            ?: return mapOf("status" to "unavailable")
        val hostConfig = node.get("HostConfig")
        val config = node.get("Config")
        return mapOf(
            "status" to "ok",
            "image" to config?.get("Image")?.asText(),
            "memoryLimitBytes" to limitOrUnlimited(hostConfig?.get("Memory")),
            "nanoCpus" to limitOrUnlimited(hostConfig?.get("NanoCpus")),
            "memoryConfigEnv" to memoryConfigEnv(config?.get("Env")),
        )
    }

    /** Docker reports 0 for "no limit configured". */
    private fun limitOrUnlimited(node: JsonNode?): Any {
        val value = node?.asLong(0) ?: 0
        return if (value > 0) value else "unlimited"
    }

    /**
     * Memory/cache-related configuration entries from the container environment
     * (`NEO4J_server_memory_*`, PostgreSQL tuning vars, ...). Entries whose KEY
     * looks credential-like are dropped so `environment.json` never captures
     * passwords, even memory-adjacent ones.
     */
    fun memoryConfigEnv(envNode: JsonNode?): List<String> =
        envNode?.mapNotNull { entry -> entry.asText().takeIf { it.isNotBlank() } }
            ?.filter { entry ->
                val key = entry.substringBefore('=')
                MEMORY_ENV_KEY.containsMatchIn(key) && !SECRET_ENV_KEY.containsMatchIn(key)
            }
            ?.sorted()
            .orEmpty()

    private fun dockerInspect(name: String): String? =
        runCatching {
            val process = ProcessBuilder("docker", "inspect", name)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (process.waitFor() == 0) output else null
        }.getOrNull()

    /** Prints every process command line inside a container, NUL-separated args joined. */
    private val PROC_CMDLINE_SCAN =
        "for f in /proc/[0-9]*/cmdline; do tr '\\0' ' ' < \"${'$'}f\" 2>/dev/null; echo; done"

    /** `-Xmx`/`-Xms` as written on the command line, e.g. `-Xmx4063240k`. */
    private val HEAP_FLAG = Regex("-X(?:mx|ms)[0-9]+[kKmMgG]?")
    private val MEMORY_ENV_KEY = Regex("(?i)(memory|heap|pagecache|cache|buffers|work_mem|shared)")
    private val SECRET_ENV_KEY = Regex("(?i)(auth|password|secret|token|credential)")
}
