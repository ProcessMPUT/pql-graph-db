package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.Instant

/** Append-only evidence. A killed process loses at most its active block, never earlier blocks. */
class BenchmarkJournal(private val directory: Path, private val runId: String,
                       protocolVersion: Int = CURRENT_BENCHMARK_PROTOCOL_VERSION) {
    private val mapper = jacksonObjectMapper()
    private var sequence = 0L
    val progress: BenchmarkProgress

    init {
        Files.createDirectories(directory)
        check(!Files.exists(directory.resolve("execution.jsonl"))) { "Run evidence already exists: $directory" }
        progress = BenchmarkProgress(directory, runId)
        event("run-started", mapOf("protocol" to protocolVersion))
    }

    @Synchronized
    fun event(kind: String, data: Any) {
        val record = mapOf("runId" to runId, "sequence" to ++sequence,
            "recordedAt" to Instant.now().toString(), "kind" to kind, "data" to data)
        Files.writeString(directory.resolve("execution.jsonl"), mapper.writeValueAsString(record) + "\n",
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }

    fun snapshot(name: String, data: Any) {
        require(name.matches(Regex("[a-z-]+")))
        Files.writeString(directory.resolve("$name.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(data))
    }

    /** Called after the latency block. File identity is its bytes, not a query-specific normalisation. */
    fun response(body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val relative = "responses/$hash.json"
        val path = directory.resolve(relative)
        Files.createDirectories(path.parent)
        if (!Files.exists(path)) Files.write(path, bytes, StandardOpenOption.CREATE_NEW)
        return relative
    }
}
