package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import kotlin.io.path.Path

enum class BenchmarkProfile(
    val warmups: Int,
    val repetitions: Int,
    val importRepetitions: Int,
    /** Fixed process/JIT warm-up chosen from the historical pilot, never reported. */
    val globalWarmupRounds: Int,
    /** Legacy protocol metadata; the current protocol does not collect an idle baseline. */
    val idleBaselineSeconds: Int = 0,
) {
    SMOKE(
        warmups = 1,
        repetitions = 3,
        importRepetitions = 1,
        globalWarmupRounds = 2,
    ),
    /** Focused stationarity check before an expensive FULL collection. */
    DIAGNOSTIC(
        warmups = 40,
        repetitions = 30,
        importRepetitions = 1,
        globalWarmupRounds = 200,
    ),
    /** Feasibility run over FULL datasets; never used for performance inference. */
    PILOT(
        warmups = 1,
        repetitions = 3,
        importRepetitions = 1,
        globalWarmupRounds = 2,
    ),
    FULL(
        warmups = 40,
        repetitions = 30,
        importRepetitions = 10,
        globalWarmupRounds = 200,
    ),
    /**
     * One thesis-grade dataset block.  It keeps the complete query family and
     * full latency/resource protocol, but uses one setup import because import
     * inference belongs to the dedicated size-scaling campaign.
     */
    BLOCK(
        warmups = 40,
        repetitions = 30,
        importRepetitions = 1,
        globalWarmupRounds = 200,
    ),
    /** Thesis-grade correction run containing only the controlled size-series queries. */
    CONTROL(
        warmups = 40,
        repetitions = 30,
        importRepetitions = 1,
        globalWarmupRounds = 200,
    ),
    /** Thesis-grade query-only size campaign after the workload audit. */
    QUERY(
        warmups = 40,
        repetitions = 30,
        importRepetitions = 1,
        globalWarmupRounds = 200,
    ),
    /** Report-only profile produced by assembling validated [BLOCK] artifacts. */
    CAMPAIGN(
        warmups = 40,
        repetitions = 30,
        importRepetitions = 1,
        globalWarmupRounds = 200,
    ),
}

enum class DatasetType {
    SYNTHETIC,
    REAL,
    /** Small hand-written application fixture; useful for smoke, not external validation. */
    FIXTURE,
}

/** See [BenchmarkSettings.datasetOrder]. */
enum class DatasetOrder {
    DECLARED,
    REVERSED,
    RANDOM,
    ;

    fun <T> apply(
        items: List<T>,
        seed: Long,
    ): List<T> =
        when (this) {
            DECLARED -> items
            REVERSED -> items.reversed()
            RANDOM -> items.shuffled(kotlin.random.Random(seed))
        }

    companion object {
        fun parse(value: String): DatasetOrder =
            entries.firstOrNull { it.name.equals(value.trim(), ignoreCase = true) }
                ?: error("Unknown BENCHMARK_DATASET_ORDER '$value'; expected one of ${entries.joinToString()}")
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class BenchmarkDatasetSpec(
    val type: DatasetType,
    val name: String,
    val series: String,
    val traces: Int? = null,
    val eventsPerTrace: Int? = null,
    val attributesPerEvent: Int? = null,
    /** Synthetic activity alphabet; absent means the historical maximum of 20 names. */
    val activityCount: Int? = null,
    /** Exact number of concept:name trace variants requested from the synthetic generator. */
    val variantCount: Int? = null,
    val resourcePath: String? = null,
    /** Persistent identifier of a published real-life dataset, when applicable. */
    val sourceDoi: String? = null,
    /** Named published family used for cross-dataset validation figures. */
    val collection: String? = null,
    /** Stable display order inside [collection], normally the challenge year. */
    val collectionOrder: Int? = null,
)

/**
 * Workload class of a benchmark query — what its cost is *expected* to depend on.
 *
 * Both REST APIs apply default hierarchical limits (`processm.compatibility.default-limits`
 * mirrors ProcessM's own `LogsService.applyLimits()`: 10 logs / 30 traces / 90 events,
 * which also **cap** any explicit `limit`). Every response is therefore bounded, but
 * the work needed before applying a scope's limit need not be: an event `ORDER BY`,
 * `GROUP BY`, or aggregate can inspect every event of each surviving trace. Scaling
 * eligibility is consequently declared per dataset series in [scalingSeries], not
 * inferred from this broad presentation class.
 *
 * - [WORKLOAD_FLOOR] — the smallest observed response window. It is a descriptive
 *   low-work reference point, not a causal estimate of transport or planning overhead:
 *   its execution path need not be the same as the other queries and it must not be
 *   subtracted from them.
 * - [WORKLOAD_WINDOW] — the response hierarchy is bounded. This usually makes the
 *   query a latency workload, but a lower-scope sort/group/aggregate may still make a
 *   particular scaling series informative.
 * - [WORKLOAD_DATA_DEPENDENT] — the result depends on proving a property of the
 *   dataset beyond the returned window (global aggregates, trace-variant grouping,
 *   or a predicate with no matches). An index may still make one implementation
 *   nearly flat; [scalingSeries] says which axes form an interpretable experiment.
 */
const val WORKLOAD_FLOOR = "floor"
const val WORKLOAD_WINDOW = "window"
const val WORKLOAD_DATA_DEPENDENT = "dataDependent"
const val CURRENT_BENCHMARK_PROTOCOL_VERSION = 25

enum class BenchmarkQueryRole {
    /** Directly tests the hierarchy-traversal hypothesis. */
    PRIMARY,

    /** Deliberate boundary case where a graph is not assumed to help. */
    CONTROL,

    /** Descriptive end-to-end reference; excluded from hypothesis tests. */
    BASELINE,

    /** Historical query retained only when replaying an older run. */
    SUPPLEMENTARY,
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class BenchmarkQuerySpec(
    val label: String,
    val query: String,
    /** One of [WORKLOAD_FLOOR], [WORKLOAD_WINDOW], [WORKLOAD_DATA_DEPENDENT]. */
    val workload: String = WORKLOAD_WINDOW,
    /** Polish description of the PQL clause under test, used verbatim in figure captions. */
    val clause: String = "",
    /** Dataset series for which this query's semantics leave a causal path from the varied axis to work. */
    val scalingSeries: List<String> = emptyList(),
    /** Reader-facing name; code labels never need to carry the explanation alone. */
    val displayName: String = label,
    /** Why this query is in the experiment and what part of PQL it exercises. */
    val purpose: String = clause,
    val role: BenchmarkQueryRole = BenchmarkQueryRole.SUPPLEMENTARY,
    /** Series on which the query is executed. Empty keeps legacy runs readable. */
    val measurementSeries: List<String> = emptyList(),
) {
    val isInferential: Boolean get() = role == BenchmarkQueryRole.PRIMARY || role == BenchmarkQueryRole.CONTROL

    fun isMeasuredFor(series: String): Boolean = measurementSeries.isEmpty() || series in measurementSeries
}

data class BenchmarkSettings(
    val profile: BenchmarkProfile,
    val localApi: String,
    val referenceApi: String,
    val processMLogin: String,
    val processMPassword: String,
    val outputRoot: Path,
    val datasetFilter: Set<String>,
    val systemFilter: Set<String>,
    val keepBenchmarkDataStores: Boolean,
    /**
     * Container running the LOCAL interpreter. When it exists, its memory is read
     * with `docker stats` — the same probe used for REFERENCE — so both systems are
     * measured identically. Empty (or a missing container) falls back to sampling
     * the application JVM's RSS on the host, which is the development setup.
     */
    val localAppContainer: String,
    val seriesFilter: Set<String> = emptySet(),
    /** Historical metadata only. The current protocol always uses the declared fixed order. */
    val datasetOrder: DatasetOrder = DatasetOrder.DECLARED,
    val datasetOrderSeed: Long = 0L,
    /** Explicit because replay must report the value used by the historical run. */
    val queryWarmups: Int = profile.warmups,
    /**
     * Version of the collection-time claims that may be made about a run.
     *
     * Version 1 (or an absent field) checked only response counts from the last
     * warm repetition. Version 2 checks counts in every measured repetition and
     * strict XES-JSON semantics of the last response. Version 3 isolates one live
     * measured dataset at a time. Version 4 restores an active state after the
     * intentionally idle memory-baseline window. Version 5 performs that
     * activation on a freshly imported throw-away datastore and deletes it,
     * matching the lifecycle that precedes later measured datasets. Version 6
     * gates broad run instability with the upper quartile of query/system
     * replicate spreads; each query's maximum spread remains its own effect floor.
     * Version 7 gives both complete applications the same finite system-level
     * cgroup budget, rejects OOM/restarted/missing-JVM containers, and extends the
     * global warm-up beyond the optimization horizon observed in a complete run.
     * Version 11 replaces the historical `hoistedGroup` response-materialization
     * workload with an aggregate-only variant. The old query used a non-total sort
     * at the default trace-limit boundary, so two valid engines could return different
     * tied subsets and make their latency samples semantically incomparable.
     * Version 12 replaces the three-order/full-matrix experiment with one fixed,
     * hypothesis-led design: 30 adjacent LOCAL/REFERENCE pairs, Wilcoxon tests and
     * paired bootstrap intervals per dataset/query, Holm correction within each
     * dataset, repeated imports, and container I/O deltas.
     * Version 13 extends the size series to one million events, replaces the
     * confounded trace-shape series with an exact trace-variant axis at fixed
     * size and shape, and validates transfer on twelve published real-life logs.
     * Version 14 adds a constant-response hierarchy-cardinality query to the
     * size and real-log families. It traverses log, trace and event scopes while
     * leaving the three-query variant family unchanged.
     * Version 15 raises per-dataset/query warm-up to 12 executions per system,
     * rejects 30-pair series whose first and last thirds differ by more than 10%,
     * and adds a focused stationarity diagnostic before the FULL collection.
     * Version 16 raises the fixed warm-up to 40 after the protocol-15 diagnostic
     * still observed 12–27% early/late drift after 12 warm-up executions.
     * Version 17 moves concurrent Docker memory polling to the unmeasured query
     * warm-ups and adds a fixed quiet period after the pre-block I/O snapshot.
     * Version 18 fully separates the latency block from Docker instrumentation:
     * memory and I/O use a duplicate unmeasured 30-pair resource block afterwards.
     * Version 19 keeps temporal drift visible for the descriptive baseline but
     * restricts invalidation to primary/control comparisons used for inference.
     * Version 20 gates the median of chronological paired REFERENCE/LOCAL ratios instead of
     * either system's absolute latency. Common-mode host drift is therefore visible
     * but does not invalidate the counterbalanced paired comparison. It also raises
     * the equal whole-system cgroup budgets to 6 GiB after the former limits were
     * reached during the instrumented resource replay.
     * Version 21 aligns that gate with the reported ratio-of-medians estimand and
     * verifies equal 3 GiB aggregate effective JVM heap ceilings from live process
     * command lines.
     * Version 22 retains the first/last-third ratio-of-medians as an explicit drift
     * diagnostic but no longer uses the arbitrary 10% cutoff to discard complete
     * paired observations. Semantic parity, complete pairs, confidence intervals,
     * paired Wilcoxon tests and Holm correction remain inferential requirements.
     * Version 23 adds complete one-real-dataset BLOCKs and validated campaign
     * assembly from raw samples. It records dataset/query context on memory samples,
     * reports resource medians for equal-sized blocks instead of duration-dependent
     * campaign totals, and adds separate BPI Challenge forest/heatmap figures.
     * Version 24 separates negative and positive LIKE controls, restricts them to the
     * controlled synthetic size series, sorts only by attributes populated by that
     * generator, and adds a thesis-grade CONTROL profile for rerunning this corrected
     * control family without repeating unchanged primary-query or import evidence.
     * Version 25 replaces the nonspecific non-null cross-scope predicate with a
     * positive selective predicate, adds a global event aggregate, a sequence grouping
     * that cannot use the import-time activity-variant cache, and an equality control
     * with real matches. It also gives every size-series query its own effect figure
     * and axis so one large effect cannot compress the remaining results.
     */
    val protocolVersion: Int = CURRENT_BENCHMARK_PROTOCOL_VERSION,
    /**
     * Defaults to the profile's value; carried explicitly so replaying an older run
     * reports the rounds that run actually performed (zero, before this phase existed)
     * rather than what the current profile would do.
     */
    val globalWarmupRounds: Int = profile.globalWarmupRounds,
    /** Legacy replay metadata; the current protocol has no post-idle activation phase. */
    val postIdleWarmupRounds: Int = 0,
) {
    companion object {
        fun fromEnvironment(profile: BenchmarkProfile): BenchmarkSettings =
            BenchmarkSettings(
                profile = profile,
                localApi = env("LOCAL_PROCESSM_API", "http://localhost:8080/api").trimEnd('/'),
                referenceApi = env("REFERENCE_PROCESSM_API", "http://localhost:80/api").trimEnd('/'),
                processMLogin = env("PROCESSM_LOGIN", "admin@example.com"),
                processMPassword = env("PROCESSM_PASSWORD", "Admin1234"),
                outputRoot = Path(env("BENCHMARK_OUTPUT_DIR", "tmp/benchmark-results")),
                datasetFilter = csvEnv("BENCHMARK_DATASET_FILTER"),
                systemFilter = csvEnv("BENCHMARK_SYSTEM_FILTER"),
                keepBenchmarkDataStores = booleanEnv("BENCHMARK_KEEP_DATASTORES", default = false),
                localAppContainer = env("LOCAL_APP_CONTAINER", "processm-interpreter"),
                seriesFilter = csvEnv("BENCHMARK_SERIES_FILTER"),
                datasetOrder = DatasetOrder.DECLARED,
                datasetOrderSeed = 0L,
            )

        private fun env(name: String, default: String): String =
            System.getenv(name)
                ?: System.getProperty(name)
                ?: default

        private fun csvEnv(name: String): Set<String> =
            (System.getenv(name) ?: System.getProperty(name))
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()

        private fun booleanEnv(name: String, default: Boolean): Boolean =
            (System.getenv(name) ?: System.getProperty(name))
                ?.let { it.equals("true", ignoreCase = true) || it == "1" || it.equals("yes", ignoreCase = true) }
                ?: default
    }
}

data class BenchmarkConfig(
    val datasets: List<BenchmarkDatasetSpec>,
    val queries: List<BenchmarkQuerySpec>,
) {
    companion object {
        private val mapper = jacksonObjectMapper()

        fun load(profile: BenchmarkProfile): BenchmarkConfig {
            val datasetsResource = resourceText("benchmark-datasets.json")
            val allDatasets = mapper.readValue(datasetsResource, BenchmarkDatasetProfiles::class.java)
            val datasets = when (profile) {
                BenchmarkProfile.SMOKE -> allDatasets.smoke
                BenchmarkProfile.DIAGNOSTIC -> allDatasets.full.filter {
                    it.name in setOf("size-1k", "size-5k", "size-20k")
                }
                BenchmarkProfile.PILOT, BenchmarkProfile.FULL, BenchmarkProfile.BLOCK, BenchmarkProfile.CONTROL,
                BenchmarkProfile.QUERY,
                BenchmarkProfile.CAMPAIGN,
                -> allDatasets.full
            }
            val allQueries = mapper.readValue(
                resourceText("benchmark-queries.json"),
                mapper.typeFactory.constructCollectionType(List::class.java, BenchmarkQuerySpec::class.java),
            ) as List<BenchmarkQuerySpec>
            val queries = when (profile) {
                BenchmarkProfile.DIAGNOSTIC ->
                    allQueries.filter { it.label in setOf("minimalWindow", "hierarchyWindow") }
                BenchmarkProfile.CONTROL -> allQueries.filter { it.role == BenchmarkQueryRole.CONTROL }
                BenchmarkProfile.QUERY -> allQueries.filter { it.isMeasuredFor("size-scaling") }
                else -> allQueries
            }
            return BenchmarkConfig(datasets = datasets, queries = queries)
        }

        private fun resourceText(name: String): String =
            Thread.currentThread().contextClassLoader.getResource(name)?.readText()
                ?: error("Missing benchmark resource: $name")
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class BenchmarkDatasetProfiles(
    val smoke: List<BenchmarkDatasetSpec>,
    val full: List<BenchmarkDatasetSpec>,
)
