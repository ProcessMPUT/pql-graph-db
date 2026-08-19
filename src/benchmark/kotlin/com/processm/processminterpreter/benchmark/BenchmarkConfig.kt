package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Path
import kotlin.io.path.Path

enum class BenchmarkProfile(
    val warmups: Int,
    val repetitions: Int,
    /**
     * Idle memory-baseline sampling window before imports (methodology 5.2).
     * FULL uses the methodology-mandated 60 s; SMOKE keeps a short window so the
     * pipeline smoke test stays fast (smoke results are never thesis evidence).
     */
    val idleBaselineSeconds: Int,
    /**
     * Executions of a throw-away dataset before the first measured one.
     *
     * Three warm-ups *per query* are not enough: the replicate datasets show that
     * the JVM's warm-up horizon spans the whole run (the same 100×10×5 dataset
     * measured at separated positions differed broadly on LOCAL). A complete v6
     * block still had Q3 ×1.57 after 40 rounds; FULL v7 therefore pays 200 rounds
     * before any number is recorded.
     */
    val globalWarmupRounds: Int,
    /**
     * Executions on a freshly imported throw-away dataset immediately after the
     * idle-memory baseline. The complete unrecorded import/query/delete cycle
     * restores an active lifecycle state before the first measured dataset,
     * without contaminating the idle samples. A complete v8 block showed that
     * 10 rounds still left the first replicate dataset broadly slower on LOCAL;
     * FULL v9 therefore repeats the same 200-round workload after the idle window.
     */
    val postIdleWarmupRounds: Int,
) {
    SMOKE(
        warmups = 1,
        repetitions = 3,
        idleBaselineSeconds = 5,
        globalWarmupRounds = 2,
        postIdleWarmupRounds = 2,
    ),
    FULL(
        warmups = 3,
        repetitions = 30,
        idleBaselineSeconds = 60,
        globalWarmupRounds = 200,
        postIdleWarmupRounds = 200,
    ),

    /**
     * Common-domain size ladder for Q2 diagnostics (10^4 … 2×10^5 events).
     * Run separately from FULL: the thesis workload does not need to pay for it, and
     * the ladder needs the headroom to leave the fixed transport floor behind.
     */
    SCALING(
        warmups = 3,
        repetitions = 30,
        idleBaselineSeconds = 60,
        globalWarmupRounds = 200,
        postIdleWarmupRounds = 200,
    ),
}

enum class DatasetType {
    SYNTHETIC,
    REAL,
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
    val resourcePath: String? = null,
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
const val BENCHMARK_SERIES_RANDOM_SEED = 20260728L
const val CURRENT_BENCHMARK_PROTOCOL_VERSION = 11
const val POST_IDLE_WARMUP_MODE = "fresh-import-query-delete"
const val REPLICATE_VALIDITY_STATISTIC = "query-spread-q3"

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
)

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
    /**
     * Order in which datasets are imported and measured — the confounder the
     * alternating L,R,L,R protocol does **not** remove. Position in the sequence
     * carries the JVM warm-up bias, and it hits the two-JVM LOCAL side hardest,
     * i.e. it biases the experiment against the implementation under study.
     *
     * METODOLOGIA §5 pkt 6 requires the three runs of a series to counterbalance
     * it: `declared`, then `reversed`, then `random` with the seed recorded in
     * `environment.json` so the order is reproducible.
     */
    val datasetOrder: DatasetOrder = DatasetOrder.DECLARED,
    val datasetOrderSeed: Long = 0L,
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
     */
    val protocolVersion: Int = CURRENT_BENCHMARK_PROTOCOL_VERSION,
    /**
     * Defaults to the profile's value; carried explicitly so replaying an older run
     * reports the rounds that run actually performed (zero, before this phase existed)
     * rather than what the current profile would do.
     */
    val globalWarmupRounds: Int = profile.globalWarmupRounds,
    /** See [BenchmarkProfile.postIdleWarmupRounds]. */
    val postIdleWarmupRounds: Int = profile.postIdleWarmupRounds,
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
                datasetOrder = DatasetOrder.parse(env("BENCHMARK_DATASET_ORDER", DatasetOrder.DECLARED.name)),
                // Keep the third counterbalancing block reproducible even when the
                // operator omits the optional environment variable. compare-runs.py
                // enforces the same preregistered seed for thesis evidence.
                datasetOrderSeed = env("BENCHMARK_DATASET_ORDER_SEED", BENCHMARK_SERIES_RANDOM_SEED.toString())
                    .toLongOrNull()
                    ?: error("BENCHMARK_DATASET_ORDER_SEED must be an integer"),
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
                BenchmarkProfile.FULL -> allDatasets.full
                BenchmarkProfile.SCALING -> allDatasets.scaling
            }
            val queries = mapper.readValue(
                resourceText("benchmark-queries.json"),
                mapper.typeFactory.constructCollectionType(List::class.java, BenchmarkQuerySpec::class.java),
            ) as List<BenchmarkQuerySpec>
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
    val scaling: List<BenchmarkDatasetSpec> = emptyList(),
)
