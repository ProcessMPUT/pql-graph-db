package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

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
            "dockerEngine" to dockerInfo(),
            "source" to sourceInfo(),
            "containers" to containers.distinct().associateWith { containerInfo(it) },
        )

    private fun hostInfo(): Map<String, Any?> =
        mapOf(
            "cpuModel" to cpuModel(),
            "logicalProcessors" to Runtime.getRuntime().availableProcessors(),
            "totalPhysicalMemoryBytes" to totalPhysicalMemoryBytes(),
        )

    private fun cpuModel(): String =
        System.getenv("PROCESSOR_IDENTIFIER")
            ?.takeIf { it.isNotBlank() }
            ?: command("sysctl", "-n", "machdep.cpu.brand_string")
            ?: runCatching {
                Files.readAllLines(Path.of("/proc/cpuinfo"))
                    .firstOrNull { it.startsWith("model name") }
                    ?.substringAfter(':')
                    ?.trim()
            }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty("os.arch", "unavailable")

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
        val state = node.get("State")
        return mapOf(
            "status" to "ok",
            "image" to config?.get("Image")?.asText(),
            "imageId" to node.get("Image")?.asText(),
            "memoryLimitBytes" to limitOrUnlimited(hostConfig?.get("Memory")),
            "memorySwapLimitBytes" to limitOrUnlimited(hostConfig?.get("MemorySwap")),
            "nanoCpus" to limitOrUnlimited(hostConfig?.get("NanoCpus")),
            "memoryConfigEnv" to memoryConfigEnv(config?.get("Env")),
            "running" to state?.get("Running")?.asBoolean(),
            "oomKilled" to state?.get("OOMKilled")?.asBoolean(),
            "restartCount" to node.get("RestartCount")?.asLong(),
        )
    }

    /**
     * Reject a container that still exists but lost its measured JVM.  The official
     * REFERENCE image keeps PostgreSQL and PID 1 alive after the Java child is killed,
     * so Docker's coarse container status alone is insufficient evidence that the
     * application survived the run.
     */
    fun runtimeIssues(
        environment: Map<String, Any?>,
        requiredJvmContainers: Collection<String>,
    ): List<String> {
        val containers = environment["containers"] as? Map<*, *>
            ?: return listOf("container environment is unavailable")
        return requiredJvmContainers.distinct().flatMap { name ->
            val details = containers[name] as? Map<*, *>
                ?: return@flatMap listOf("$name: container facts are unavailable")
            buildList {
                if (details["status"] != "ok") add("$name: docker inspect failed")
                if (details["running"] != true) add("$name: container is not running")
                if (details["oomKilled"] != false) add("$name: OOM kill was observed")
                if ((details["restartCount"] as? Number)?.toLong() != 0L) {
                    add("$name: restart count is ${details["restartCount"] ?: "unknown"}")
                }
                if (details["effectiveJvmHeap"] in setOf(null, "unavailable")) {
                    add("$name: measured JVM process is absent")
                }
            }
        }
    }

    private fun dockerInfo(): Map<String, Any?> =
        command("docker", "info", "--format", "{{json .}}")
            ?.let(::parseDockerInfo)
            ?: mapOf("status" to "unavailable")

    /** Parses the stable subset of `docker info --format '{{json .}}'`. */
    fun parseDockerInfo(json: String): Map<String, Any?> {
        val node = runCatching { mapper.readTree(json) }.getOrNull()
            ?: return mapOf("status" to "unavailable")
        return mapOf(
            "status" to "ok",
            "serverVersion" to node.get("ServerVersion")?.asText(),
            "operatingSystem" to node.get("OperatingSystem")?.asText(),
            "osType" to node.get("OSType")?.asText(),
            "architecture" to node.get("Architecture")?.asText(),
            "logicalProcessors" to node.get("NCPU")?.asLong(),
            // Docker Desktop runs a Linux VM. This is the actual global memory
            // budget shared by the containers, not the host's physical RAM.
            "totalMemoryBytes" to node.get("MemTotal")?.asLong(),
        )
    }

    private fun sourceInfo(): Map<String, Any?> {
        val commit = command("git", "rev-parse", "HEAD") ?: "unavailable"
        // Generated benchmark outputs live in ignored tmp/, so untracked files can
        // safely be included. Otherwise a new, uncommitted source file could change
        // the measured code while environment.json still claimed a clean tree.
        val status = commandAllowEmpty("git", "status", "--porcelain", "--untracked-files=all")
        return mapOf(
            "gitCommit" to commit,
            "gitDirty" to when {
                status == null -> "unavailable"
                status.isEmpty() -> false
                else -> true
            },
        )
    }

    private fun command(vararg command: String): String? =
        commandAllowEmpty(*command)?.takeIf { it.isNotBlank() }

    private fun commandAllowEmpty(vararg command: String): String? =
        runCatching {
            val process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val output = process.inputStream.readAllBytes().toString(Charsets.UTF_8).trim()
            if (process.waitFor() == 0) output else null
        }.getOrNull()

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
