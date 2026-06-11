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
