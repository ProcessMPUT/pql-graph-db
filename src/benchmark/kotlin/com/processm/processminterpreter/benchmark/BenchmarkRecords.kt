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
    /** Exact mean plus robust/order statistics describing the trace-length distribution. */
    val meanEventsPerTrace: Double = eventsPerTrace.toDouble(),
    val medianEventsPerTrace: Double = eventsPerTrace.toDouble(),
    val p95EventsPerTrace: Int = eventsPerTrace,
    val maxEventsPerTrace: Int = eventsPerTrace,
    /** Distinct event concept:name values and concept:name trace sequences. */
    val activityCount: Int = 0,
    val variantCount: Int = 0,
    val sourceDoi: String? = null,
    /** SHA-256 of the exact compressed or plain file submitted to both systems. */
    val fileSha256: String = "",
    /** Mean count of all direct and nested XES attributes belonging to an event. */
    val meanEventAttributes: Double = attributesPerEvent.toDouble(),
    /** Named published family, e.g. `bpi-challenge`; independent from the experimental series. */
    val collection: String? = null,
    /** Stable display order inside [collection]. */
    val collectionOrder: Int? = null,
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
    /** Quartiles, so charts can draw the same spread the tables print. */
    val q1Seconds: Double,
    val q3Seconds: Double,
    val p95Seconds: Double,
    val minSeconds: Double,
    val maxSeconds: Double,
    val averageSeconds: Double,
)

/** Strictly positive delta — the only case that is an attributable measurement. */
const val STORAGE_STATUS_OK = "OK"

/** Delta is exactly zero: the import did not move the store past a filesystem allocation boundary. */
const val STORAGE_STATUS_BELOW_GRANULARITY = "BELOW_ALLOCATION_GRANULARITY"

/**
 * Delta is negative — the store shrank across the import (autovacuum, page reuse,
 * WAL recycling). Not "below granularity" and not a small number: no per-dataset
 * quantity can be recovered from it, so it must never be tabulated or plotted.
 */
const val STORAGE_STATUS_CONTAMINATED = "CONTAMINATED_NEGATIVE_DELTA"

/** The probe itself did not return a size. */
const val STORAGE_STATUS_UNAVAILABLE = "UNAVAILABLE"

data class StorageBenchmarkResult(
    val system: String,
    val datasetName: String,
    val beforeBytes: Long?,
    val afterBytes: Long?,
    val deltaBytes: Long?,
    val deltaToXesRatio: Double?,
    val deltaToGzipRatio: Double?,
    val status: String,
) {
    /** True only for a delta that may be tabulated, plotted or fitted. */
    val isAttributable: Boolean get() = status == STORAGE_STATUS_OK && (deltaBytes ?: 0L) > 0L
}

/** One row from the thesis-grade storage probe: a fresh Compose stack per dataset. */
data class IsolatedStorageScalingResult(
    val datasetName: String,
    val system: String,
    val measurementMode: String,
    val stackPreparationId: String,
    val gitCommit: String,
    val localAppImageId: String,
    val localDbImageId: String,
    val referenceImageId: String,
    val beforeBytes: Long,
    val afterBytes: Long,
    val deltaBytes: Long,
    val xesBytes: Long,
    val xesGzBytes: Long,
    val deltaToXesRatio: Double,
    val deltaToGzipRatio: Double,
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
    /** Measured component, e.g. `processm-server`, `processm-neo4j`, `processm-interpreter`. */
    val component: String,
    val bytes: Long,
    /** Empty only for historical artifacts which predate contextual resource samples. */
    val datasetName: String = "",
    /** Query label or another measured operation inside [datasetName]. */
    val operationLabel: String = "",
)

data class MemorySummary(
    val component: String,
    val phase: String,
    val medianBytes: Long,
    val peakBytes: Long,
    val datasetName: String = "",
    val operationLabel: String = "",
)

/**
 * Counter deltas observed outside the timed interval for one import or one whole
 * query-repetition block. Null operation counts mean that the host did not expose
 * cgroup v2 `io.stat`; byte counters still remain usable in that case.
 */
data class ContainerIoBenchmarkResult(
    val system: String,
    val phase: String,
    val datasetName: String,
    val operationLabel: String,
    val run: Int,
    val component: String,
    val blockReadBytes: Long?,
    val blockWriteBytes: Long?,
    val blockReadOperations: Long?,
    val blockWriteOperations: Long?,
    val networkReceiveBytes: Long?,
    val networkTransmitBytes: Long?,
    val status: String,
    val details: String = "",
)

data class BenchmarkComparisonResult(
    val metric: String,
    val datasetName: String,
    val series: String,
    val operationLabel: String,
    val displayName: String,
    val role: String,
    val pairs: Int,
    val localMedianSeconds: Double?,
    val referenceMedianSeconds: Double?,
    /** Effect definition used by the current paired methodology: REFERENCE / LOCAL. */
    val ratioReferenceToLocal: Double?,
    val confidenceLow: Double?,
    val confidenceHigh: Double?,
    val rawPValue: Double?,
    val holmPValue: Double?,
    val verdict: String,
    val status: String,
    /** Size of each separated first/last window used by the temporal-stability gate. */
    val stabilityWindowSamples: Int? = null,
    val localEarlyMedianSeconds: Double? = null,
    val localLateMedianSeconds: Double? = null,
    val localEarlyLateRatio: Double? = null,
    val referenceEarlyMedianSeconds: Double? = null,
    val referenceLateMedianSeconds: Double? = null,
    val referenceEarlyLateRatio: Double? = null,
    /** REFERENCE/LOCAL ratios of medians in the separated paired chronological windows. */
    val pairedEarlyMedianRatio: Double? = null,
    val pairedLateMedianRatio: Double? = null,
    val pairedEarlyLateRatio: Double? = null,
    val details: String = "",
)
