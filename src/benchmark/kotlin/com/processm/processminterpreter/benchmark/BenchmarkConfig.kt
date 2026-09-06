package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

const val CURRENT_BENCHMARK_PROTOCOL_VERSION = 27

enum class DatasetType { SYNTHETIC, REAL, FIXTURE }

@JsonIgnoreProperties(ignoreUnknown = true)
data class BenchmarkDatasetSpec(
    val type: DatasetType,
    val name: String,
    val series: String,
    val traces: Int? = null,
    val eventsPerTrace: Int? = null,
    val attributesPerEvent: Int? = null,
    /** Synthetic activity alphabet; defaults to at most 20 names. */
    val activityCount: Int? = null,
    /** Exact number of concept:name trace variants requested from the synthetic generator. */
    val variantCount: Int? = null,
    /** Exact percentage of evenly interleaved traces whose attr_1 equals "hit". */
    val matchingTracePercent: Int? = null,
    /** Repeat the same cost distribution instead of increasing it with trace length. */
    val costCycleLength: Int? = null,
    val resourcePath: String? = null,
    /** Persistent identifier of a published real-life dataset, when applicable. */
    val sourceDoi: String? = null,
    /** Named published family used for cross-dataset validation figures. */
    val collection: String? = null,
    /** Stable display order inside [collection], normally the challenge year. */
    val collectionOrder: Int? = null,
)

enum class BenchmarkQueryRole {
    /** Directly tests the hierarchy-traversal hypothesis. */
    PRIMARY,

    /** Deliberate boundary case where a graph is not assumed to help. */
    CONTROL,

    /** Descriptive end-to-end reference; excluded from hypothesis tests. */
    BASELINE,

    /** Controlled descriptive axis, outside the predeclared hypothesis family. */
    DESCRIPTIVE,

}

@JsonIgnoreProperties(ignoreUnknown = true)
data class BenchmarkQuerySpec(
    val label: String,
    val query: String,
    /** Descriptive response-window class. */
    val workload: String = "window",
    /** Polish description of the PQL clause under test, used verbatim in figure captions. */
    val clause: String = "",
    /** Dataset series for which this query's semantics leave a causal path from the varied axis to work. */
    val scalingSeries: List<String> = emptyList(),
    /** Reader-facing name; code labels never need to carry the explanation alone. */
    val displayName: String = label,
    /** Why this query is in the experiment and what part of PQL it exercises. */
    val purpose: String = clause,
    val role: BenchmarkQueryRole = BenchmarkQueryRole.BASELINE,
    /** Series on which the query is executed. */
    val measurementSeries: List<String> = emptyList(),
    /** Optional restriction within those series, e.g. the one log used for response windows. */
    val measurementDatasets: List<String> = emptyList(),
    /** Independently specified results for controlled synthetic cases, checked on both systems. */
    val expectedResponses: Map<String, ExpectedBenchmarkResponse> = emptyMap(),
    val minimumResponseEvents: Int = 0,
    val minimumResponseLogs: Int = 0,
    val maximumResponseEvents: Int? = null,
) {
    fun isMeasuredFor(datasetName: String, series: String): Boolean =
        series in measurementSeries && (measurementDatasets.isEmpty() || datasetName in measurementDatasets)
}

data class BenchmarkConfig(
    val datasets: List<BenchmarkDatasetSpec>,
    val queries: List<BenchmarkQuerySpec>,
) {
    companion object {
        fun catalog(): BenchmarkConfig {
            val mapper = jacksonObjectMapper()
            fun resource(name: String) = requireNotNull(BenchmarkConfig::class.java.classLoader.getResourceAsStream(name))
                .use { mapper.readTree(it) }
            return BenchmarkConfig(
                resource("benchmark-datasets.json")["full"].map { mapper.treeToValue(it, BenchmarkDatasetSpec::class.java) },
                resource("benchmark-queries.json").map { mapper.treeToValue(it, BenchmarkQuerySpec::class.java) },
            )
        }
    }
}
