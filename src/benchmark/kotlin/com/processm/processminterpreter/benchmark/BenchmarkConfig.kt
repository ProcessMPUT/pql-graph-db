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
     * measured 1st, 6th and 10th in the sequence differed by up to ×2,4 on LOCAL).
     * This phase pays that cost before any number is recorded.
     */
    val globalWarmupRounds: Int,
) {
    SMOKE(warmups = 1, repetitions = 3, idleBaselineSeconds = 5, globalWarmupRounds = 2),
    FULL(warmups = 3, repetitions = 30, idleBaselineSeconds = 60, globalWarmupRounds = 40),

    /**
     * Deep size ladder for the Q2 scaling chapter (10^4 … 10^6 events, three decades).
     * Run separately from FULL: the thesis workload does not need to pay for it, and
     * the ladder needs the headroom to leave the fixed transport floor behind.
     */
    SCALING(warmups = 3, repetitions = 30, idleBaselineSeconds = 60, globalWarmupRounds = 40),
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
 * which also **cap** any explicit `limit`). Every response is therefore bounded, and
 * simply dropping the `limit` clause does not produce an O(n) query. Whether a query
 * scales with the dataset is decided by its semantics, not by its window:
 *
 * - [WORKLOAD_FLOOR] — the smallest possible window. Measures the cost that is present
 *   in every other number: HTTP transport, authentication, parsing, planning. Subtract
 *   it (or read it as the baseline) before attributing anything to the storage engine.
 * - [WORKLOAD_WINDOW] — the engine can satisfy the query from a bounded window and push
 *   the limit down, so the cost is O(window), not O(n). Useful for latency comparison,
 *   **useless for scaling** — a size axis has no causal path to the measured time.
 * - [WORKLOAD_FULL_PASS] — the semantics force a pass over the whole log before the
 *   window can be applied (global aggregates, trace-variant grouping, a predicate that
 *   matches nothing, `like` on an unindexed attribute). These are the only queries whose
 *   scaling figures carry information.
 */
const val WORKLOAD_FLOOR = "floor"
const val WORKLOAD_WINDOW = "window"
const val WORKLOAD_FULL_PASS = "fullPass"

@JsonIgnoreProperties(ignoreUnknown = true)
data class BenchmarkQuerySpec(
    val label: String,
    val query: String,
    /** One of [WORKLOAD_FLOOR], [WORKLOAD_WINDOW], [WORKLOAD_FULL_PASS]. */
    val workload: String = WORKLOAD_WINDOW,
    /** Polish description of the PQL clause under test, used verbatim in figure captions. */
    val clause: String = "",
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
     * Defaults to the profile's value; carried explicitly so replaying an older run
     * reports the rounds that run actually performed (zero, before this phase existed)
     * rather than what the current profile would do.
     */
    val globalWarmupRounds: Int = profile.globalWarmupRounds,
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
                datasetOrderSeed = env("BENCHMARK_DATASET_ORDER_SEED", "").toLongOrNull()
                    ?: System.currentTimeMillis(),
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
