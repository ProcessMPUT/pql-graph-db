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
    val startedAt: String = "",
    val startedNanos: Long? = null,
    val finishedNanos: Long? = null,
    val httpStatus: Int? = null,
)

data class QueryBenchmarkResult(
    val system: String,
    val datasetName: String,
    val queryLabel: String,
    val run: Int,
    val seconds: Double,
    val status: String,
    val responseBytes: Long,
    /** `warmup` (excluded from estimates) or `warm` (measured repetition). */
    val phase: String = QUERY_PHASE_WARM,
    /** Response counts parsed from the XES-JSON body (Q4 parity input). */
    val logCount: Int = 0,
    val traceCount: Int = 0,
    val eventCount: Int = 0,
    val details: String = "",
    val startedAt: String = "",
    val startedNanos: Long? = null,
    val finishedNanos: Long? = null,
    val httpStatus: Int? = null,
    val executionIndex: Int? = null,
    val responsePath: String = "",
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

data class BenchmarkSystem(val name: String, val apiBase: String, val containers: Set<String>)

data class MemorySample(
    /** ISO-8601 wall-clock timestamp of the sample. */
    val timestamp: String,
    /** Sampling phase: `import-resource` or `queries`. */
    val phase: String,
    /** Measured component, e.g. `processm-server`, `processm-neo4j`, `processm-interpreter`. */
    val component: String,
    val bytes: Long,
    /** Dataset whose operation is being observed. */
    val datasetName: String = "",
    /** Query label or another measured operation inside [datasetName]. */
    val operationLabel: String = "",
    val completedAt: String = "",
    val activeSystem: String = "",
    val withinWindow: Boolean = true,
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
    val completedOperations: Int? = null,
    val windowStartedNanos: Long? = null,
    val windowFinishedNanos: Long? = null,
)
