package com.processm.processminterpreter.benchmark

enum class QueryStepKind {
    /** First execution per (system, dataset, query), recorded with phase `cold`. */
    COLD,

    /** Unrecorded warmup execution. */
    WARMUP,

    /** Recorded repetition with phase `warm`. */
    MEASURED,
}

data class QueryExecutionStep(
    val systemIndex: Int,
    val kind: QueryStepKind,
    /** 0 for cold and warmup steps, 1..repetitions for measured steps. */
    val run: Int,
)

const val QUERY_PHASE_COLD = "cold"
const val QUERY_PHASE_WARM = "warm"
const val QUERY_STATUS_MISMATCH = "MISMATCH"

/**
 * Builds the per-(dataset, query) execution order required by the methodology (section 5.4):
 * 1. one recorded cold execution per system, before any warmup,
 * 2. unrecorded warmups, interleaved between systems,
 * 3. recorded repetitions, interleaved between systems (A,B,A,B,...).
 */
fun buildQueryExecutionPlan(
    systemCount: Int,
    warmups: Int,
    repetitions: Int,
): List<QueryExecutionStep> =
    buildList {
        repeat(systemCount) { systemIndex ->
            add(QueryExecutionStep(systemIndex, QueryStepKind.COLD, run = 0))
        }
        repeat(warmups) {
            repeat(systemCount) { systemIndex ->
                add(QueryExecutionStep(systemIndex, QueryStepKind.WARMUP, run = 0))
            }
        }
        repeat(repetitions) { repetition ->
            repeat(systemCount) { systemIndex ->
                add(QueryExecutionStep(systemIndex, QueryStepKind.MEASURED, run = repetition + 1))
            }
        }
    }

/**
 * Response-count parity check for one completed (dataset, query) pair (methodology 2.6):
 * compares log/trace/event counts of the LAST successful warm sample of each system.
 * On mismatch every successful sample of the pair is downgraded to `MISMATCH` while
 * keeping its timing data; failed samples keep their `ERROR` status.
 */
fun applyResponseCountParity(samples: List<QueryBenchmarkResult>): List<QueryBenchmarkResult> {
    val lastWarmBySystem = samples
        .filter { it.phase == QUERY_PHASE_WARM && it.status == "OK" }
        .groupBy { it.system }
        .mapValues { (_, rows) -> rows.maxBy { it.run } }
    if (lastWarmBySystem.size < 2) return samples

    val counts = lastWarmBySystem.mapValues { (_, row) -> Triple(row.logCount, row.traceCount, row.eventCount) }
    if (counts.values.distinct().size <= 1) return samples

    val description = counts.entries
        .sortedBy { it.key }
        .joinToString("; ") { (system, count) ->
            "$system logs=${count.first} traces=${count.second} events=${count.third}"
        }
    return samples.map { sample ->
        if (sample.status == "OK") {
            sample.copy(status = QUERY_STATUS_MISMATCH, details = "Response count mismatch: $description")
        } else {
            sample
        }
    }
}
