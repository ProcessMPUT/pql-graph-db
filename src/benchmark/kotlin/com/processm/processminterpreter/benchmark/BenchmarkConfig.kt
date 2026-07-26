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
) {
    SMOKE(warmups = 1, repetitions = 3, idleBaselineSeconds = 5),
    FULL(warmups = 3, repetitions = 30, idleBaselineSeconds = 60),
}

enum class DatasetType {
    SYNTHETIC,
    REAL,
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

@JsonIgnoreProperties(ignoreUnknown = true)
data class BenchmarkQuerySpec(
    val label: String,
    val query: String,
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

private data class BenchmarkDatasetProfiles(
    val smoke: List<BenchmarkDatasetSpec>,
    val full: List<BenchmarkDatasetSpec>,
)
