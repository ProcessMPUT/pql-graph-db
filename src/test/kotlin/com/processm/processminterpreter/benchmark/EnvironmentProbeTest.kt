package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EnvironmentProbeTest {
    @Test
    fun `parses image, limits, and memory env from docker inspect output`() {
        val inspectJson = """
            [
              {
                "Config": {
                  "Image": "neo4j:5.26-community",
                  "Env": [
                    "NEO4J_server_memory_heap_max__size=4G",
                    "NEO4J_server_memory_pagecache_size=2G",
                    "NEO4J_AUTH=neo4j/secretpassword",
                    "PATH=/usr/local/bin",
                    "POSTGRES_PASSWORD=super-secret"
                  ]
                },
                "HostConfig": {
                  "Memory": 8589934592,
                  "NanoCpus": 4000000000
                },
                "Image": "sha256:abc123"
              }
            ]
        """.trimIndent()

        val info = EnvironmentProbe.parseContainerInfo(inspectJson)

        assertEquals("ok", info["status"])
        assertEquals("neo4j:5.26-community", info["image"])
        assertEquals("sha256:abc123", info["imageId"])
        assertEquals(8589934592L, info["memoryLimitBytes"])
        assertEquals(4000000000L, info["nanoCpus"])
        assertEquals(
            listOf(
                "NEO4J_server_memory_heap_max__size=4G",
                "NEO4J_server_memory_pagecache_size=2G",
            ),
            info["memoryConfigEnv"],
            "credential-like keys must never reach environment.json",
        )
    }

    @Test
    fun `unset docker limits are reported as unlimited, not zero`() {
        val info = EnvironmentProbe.parseContainerInfo(
            """[{"Config": {"Image": "processm/processm-server-full", "Env": []}, "HostConfig": {"Memory": 0, "NanoCpus": 0}}]""",
        )

        assertEquals("unlimited", info["memoryLimitBytes"])
        assertEquals("unlimited", info["nanoCpus"])
    }

    @Test
    fun `malformed inspect output degrades to unavailable`() {
        assertEquals(mapOf<String, Any?>("status" to "unavailable"), EnvironmentProbe.parseContainerInfo("not json"))
    }

    @Test
    fun `docker info captures the VM resource budget rather than only host RAM`() {
        val info = EnvironmentProbe.parseDockerInfo(
            """{"ServerVersion":"28.3.2","OperatingSystem":"Docker Desktop","OSType":"linux","Architecture":"aarch64","NCPU":10,"MemTotal":8321499136}""",
        )

        assertEquals("ok", info["status"])
        assertEquals("Docker Desktop", info["operatingSystem"])
        assertEquals(8321499136L, info["totalMemoryBytes"])
        assertEquals(10L, info["logicalProcessors"])
    }
}
