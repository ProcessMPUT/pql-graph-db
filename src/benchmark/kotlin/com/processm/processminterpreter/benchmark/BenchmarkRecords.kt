package com.processm.processminterpreter.benchmark

import java.nio.file.Path

data class PreparedDataset(
    val name: String,
    val series: String,
    val file: Path,
    val traces: Int,
    val eventsPerTrace: Int,
    val totalEvents: Int,
    val attributesPerEvent: Int,
    val totalAttributes: Int,
    val xesBytes: Long,
    val xesGzBytes: Long,
)

data class ImportBenchmarkResult(
    val system: String,
    val datasetName: String,
    val run: Int,
    val seconds: Double,
    val status: String,
    val dataStoreId: String,
    val logCount: Int,
    val details: String = "",
)

data class QueryBenchmarkResult(
    val system: String,
    val datasetName: String,
    val queryLabel: String,
    val run: Int,
    val seconds: Double,
    val status: String,
    val responseBytes: Long,
    /** `cold` (first execution, before warmups) or `warm` (measured repetition). */
    val phase: String = QUERY_PHASE_WARM,
    /** Response counts parsed from the XES-JSON body (Q4 parity input). */
    val logCount: Int = 0,
    val traceCount: Int = 0,
    val eventCount: Int = 0,
    val details: String = "",
)

data class QueryBenchmarkSummary(
    val system: String,
    val datasetName: String,
    val queryLabel: String,
    val samples: Int,
    val medianSeconds: Double,
    val p95Seconds: Double,
    val minSeconds: Double,
    val maxSeconds: Double,
    val averageSeconds: Double,
)

data class StorageBenchmarkResult(
    val system: String,
    val datasetName: String,
    val beforeBytes: Long?,
    val afterBytes: Long?,
    val deltaBytes: Long?,
    val deltaToXesRatio: Double?,
    val deltaToGzipRatio: Double?,
    val status: String,
)

data class RoundtripBenchmarkResult(
    val datasetName: String,
    val status: String,
    val differencesCount: Int,
    val detailsPath: String,
)

data class DataStoreCleanupResult(
    val system: String,
    val dataStoreName: String,
    val dataStoreId: String,
    val status: String,
    val details: String = "",
)

data class CreatedDataStoreHandle(
    val system: BenchmarkSystem,
    val name: String,
    val dataStoreId: String,
)

data class ImportedDatasetHandle(
    val system: BenchmarkSystem,
    val dataset: PreparedDataset,
    val dataStoreId: String,
)

data class BenchmarkSystem(
    val name: String,
    val apiBase: String,
    val storage: StorageProbe,
)

data class StorageProbe(
    val container: String,
    val path: String,
    val sizeCommand: String? = null,
    val flushCommand: String? = null,
)

data class MemorySample(
    /** ISO-8601 wall-clock timestamp of the sample. */
    val timestamp: String,
    /** Sampling phase: `idle` (baseline before imports) or `queries`. */
    val phase: String,
    /** Measured component, e.g. `processm-server`, `processm-neo4j`, `local-jvm`. */
    val component: String,
    val bytes: Long,
)

data class MemorySummary(
    val component: String,
    val phase: String,
    val medianBytes: Long,
    val peakBytes: Long,
)
