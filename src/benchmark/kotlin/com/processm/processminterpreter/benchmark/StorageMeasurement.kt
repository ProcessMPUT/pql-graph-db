package com.processm.processminterpreter.benchmark

class DockerStorageMeter {
    fun measureStable(
        probe: StorageProbe,
        attempts: Int = 3,
        delayMillis: Long = 750,
    ): Long? {
        flush(probe)
        var previous: Long? = null
        repeat(attempts) {
            val current = measure(probe)
            if (current != null && current == previous) {
                return current
            }
            previous = current
            Thread.sleep(delayMillis)
        }
        return previous
    }

    fun measure(probe: StorageProbe): Long? {
        val sizeCommand = probe.sizeCommand ?: "du -sk ${probe.path} 2>/dev/null | awk '{print \$1 * 1024}'"
        val command = listOf(
            "docker",
            "exec",
            probe.container,
            "sh",
            "-c",
            sizeCommand,
        )
        return try {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            if (exit == 0) output.lineSequence().firstOrNull()?.trim()?.toLongOrNull() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun flush(probe: StorageProbe) {
        val command = probe.flushCommand ?: return
        runCatching {
            val process = ProcessBuilder(
                listOf(
                    "docker",
                    "exec",
                    probe.container,
                    "sh",
                    "-c",
                    command,
                ),
            )
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().readText()
            process.waitFor()
        }
    }
}
