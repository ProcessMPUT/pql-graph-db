package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

/** Small operational cursor, separate from immutable result evidence. No timer or heartbeat claims progress. */
class BenchmarkProgress(private val directory: Path, private val runId: String) {
    private val mapper = jacksonObjectMapper()
    private var current: Map<String, Any?>? = null
    private var lastCompleted: Map<String, Any?>? = null
    private var lastFailure: Map<String, Any?>? = null
    private var completedOperations = 0L

    init { publish("RUNNING") }

    fun <T> operation(
        phase: String,
        dataset: String? = null,
        query: String? = null,
        system: String? = null,
        repetition: Int? = null,
        total: Int? = null,
        block: () -> T,
    ): T {
        check(current == null) { "Progress operations cannot be nested" }
        current = mapOf("phase" to phase, "dataset" to dataset, "query" to query,
            "system" to system, "repetition" to repetition, "total" to total,
            "startedAt" to Instant.now().toString())
        publish("RUNNING")
        try {
            val result = block()
            lastCompleted = current!! + ("completedAt" to Instant.now().toString())
            completedOperations++
            return result
        } catch (error: Throwable) {
            lastFailure = current!! + mapOf("failedAt" to Instant.now().toString(),
                "type" to error.javaClass.simpleName, "message" to error.message)
            throw error
        } finally {
            current = null
            publish("RUNNING")
        }
    }

    fun finish(failure: Throwable?) {
        check(current == null) { "Cannot finish while an operation is active" }
        publish(if (failure == null) "COMPLETED" else "FAILED", failure)
    }

    private fun publish(status: String, failure: Throwable? = null) {
        val temporary = directory.resolve("live-progress.json.tmp")
        Files.writeString(temporary, mapper.writeValueAsString(mapOf(
            "runId" to runId, "status" to status, "updatedAt" to Instant.now().toString(),
            "current" to current, "completedOperations" to completedOperations,
            "lastCompleted" to lastCompleted, "lastFailure" to lastFailure,
            "failure" to failure?.let { mapOf("type" to it.javaClass.simpleName, "message" to it.message) },
        )))
        Files.move(temporary, directory.resolve("live-progress.json"),
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
