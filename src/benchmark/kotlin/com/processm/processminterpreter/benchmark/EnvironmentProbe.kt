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
        dockerInspect(name)?.let(::parseContainerInfo) ?: mapOf("status" to "unavailable")

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

    private val MEMORY_ENV_KEY = Regex("(?i)(memory|heap|pagecache|cache|buffers|work_mem|shared)")
    private val SECRET_ENV_KEY = Regex("(?i)(auth|password|secret|token|credential)")
}
