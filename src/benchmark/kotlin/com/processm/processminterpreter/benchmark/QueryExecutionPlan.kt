package com.processm.processminterpreter.benchmark

enum class QueryStepKind {
    /** Unrecorded warmup execution. */
    WARMUP,

    /** Recorded repetition with phase `warm`. */
    MEASURED,
}

data class QueryExecutionStep(
    val systemIndex: Int,
    val kind: QueryStepKind,
    /** 0 for warmup steps, 1..repetitions for measured steps. */
    val run: Int,
)

const val QUERY_PHASE_COLD = "cold"
const val QUERY_PHASE_WARM = "warm"
const val QUERY_STATUS_MISMATCH = "MISMATCH"

/**
 * Builds the per-(dataset, query) execution order required by the methodology (section 5.4):
 * 1. unrecorded warmups, counterbalanced between systems,
 * 2. recorded paired repetitions in AB/BA order so neither system is always first.
 *
 * The current paired protocol deliberately has no recorded "cold" sample. A first execution after
 * a global JVM warm-up is neither a reproducible cold start nor part of the warm
 * distribution used to answer the research question.
 */
fun buildQueryExecutionPlan(
    systemCount: Int,
    warmups: Int,
    repetitions: Int,
    initialSystemIndex: Int = 0,
): List<QueryExecutionStep> =
    buildList {
        require(systemCount > 0) { "systemCount must be positive" }
        require(initialSystemIndex in 0 until systemCount) { "initialSystemIndex must identify a system" }

        fun systemOrder(startOffset: Int): List<Int> =
            (0 until systemCount).map { (initialSystemIndex + startOffset + it) % systemCount }

        repeat(warmups) { warmup ->
            systemOrder(warmup).forEach { systemIndex ->
                add(QueryExecutionStep(systemIndex, QueryStepKind.WARMUP, run = 0))
            }
        }
        repeat(repetitions) { repetition ->
            systemOrder(repetition).forEach { systemIndex ->
                add(QueryExecutionStep(systemIndex, QueryStepKind.MEASURED, run = repetition + 1))
            }
        }
    }

/**
 * Response parity check for one completed (dataset, query) pair (methodology 2.6):
 * compares counts for every successful warm repetition and, when bodies are supplied,
 * strict XES-JSON semantics of the LAST successful warm sample of each system.
 * On mismatch every successful sample of the pair is downgraded to `MISMATCH` while
 * keeping its timing data; failed samples keep their `ERROR` status.
 */
fun applyResponseParity(
    samples: List<QueryBenchmarkResult>,
    lastWarmBodies: Map<String, String> = emptyMap(),
): List<QueryBenchmarkResult> {
    val lastWarmBySystem = samples
        .filter { it.phase == QUERY_PHASE_WARM && it.status == "OK" }
        .groupBy { it.system }
        .mapValues { (_, rows) -> rows.maxBy { it.run } }
    if (lastWarmBySystem.size < 2) return samples

    val countMismatch = samples
        .filter { it.phase == QUERY_PHASE_WARM && it.status == "OK" }
        .groupBy { it.run }
        .toSortedMap()
        .entries
        .firstNotNullOfOrNull { (run, rows) ->
            val counts = rows.associate { row ->
                row.system to Triple(row.logCount, row.traceCount, row.eventCount)
            }
            if (counts.size >= 2 && counts.values.distinct().size > 1) run to counts else null
        }
    val semantic = if (countMismatch == null) {
        val local = lastWarmBodies["local"]
        val reference = lastWarmBodies["reference"]
        if (local != null && reference != null) XesJsonSemanticParity.compare(local, reference) else null
    } else {
        null
    }
    if (countMismatch == null && semantic?.matches != false) return samples

    val description = if (countMismatch != null) {
        countMismatch.second.entries
            .sortedBy { it.key }
            .joinToString("; ") { (system, count) ->
                "$system logs=${count.first} traces=${count.second} events=${count.third}"
            }
            .let { "Response count mismatch: warm run ${countMismatch.first}; $it" }
    } else {
        "Semantic response mismatch: ${semantic?.details.orEmpty()}"
    }
    return samples.map { sample ->
        if (sample.status == "OK") {
            sample.copy(status = QUERY_STATUS_MISMATCH, details = description)
        } else {
            sample
        }
    }
}
