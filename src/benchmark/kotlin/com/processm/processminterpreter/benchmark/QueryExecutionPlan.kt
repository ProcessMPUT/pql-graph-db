package com.processm.processminterpreter.benchmark

enum class QueryStepKind {
    /** Warmup execution, recorded diagnostically but excluded from estimates. */
    WARMUP,

    /** Recorded repetition with phase `warm`. */
    MEASURED,
}

/** Every measured pair requires complete evidence and strict XES-JSON semantic equality. */
fun applyEveryResponseParity(
    samples: List<QueryBenchmarkResult>,
    bodies: Map<Pair<Int, String>, String>,
): List<QueryBenchmarkResult> = samples.groupBy { it.run }.toSortedMap().values.flatMap { pair ->
    val complete = pair.size == 2 && pair.map { it.system }.toSet() == setOf("local", "reference") &&
        pair.all { it.status == "OK" && (it.run to it.system) in bodies }
    val problem = when {
        !complete -> "Missing successful pair or semantic response evidence"
        pair.map { Triple(it.logCount, it.traceCount, it.eventCount) }.distinct().size != 1 -> "Response count mismatch"
        else -> XesJsonSemanticParity.compare(
            bodies.getValue(pair.first().run to "local"), bodies.getValue(pair.first().run to "reference"),
        ).let { if (it.matches) null else "Semantic response mismatch: ${it.details}" }
    }
    pair.map { if (problem != null && it.status == "OK") it.copy(status = QUERY_STATUS_MISMATCH, details = problem) else it }
}

data class QueryExecutionStep(
    val systemIndex: Int,
    val kind: QueryStepKind,
    /** 0 for warmup steps, 1..repetitions for measured steps. */
    val run: Int,
)

const val QUERY_PHASE_WARM = "warm"
const val QUERY_STATUS_MISMATCH = "MISMATCH"

/**
 * Builds the per-(dataset, query) execution order required by the methodology (section 5.4):
 * 1. diagnostic warmups, counterbalanced between systems,
 * 2. recorded paired repetitions in AB/BA order so neither system is always first.
 */
fun buildQueryExecutionPlan(
    warmups: Int,
    repetitions: Int,
    initialSystemIndex: Int = 0,
): List<QueryExecutionStep> =
    buildList {
        require(warmups >= 0 && repetitions >= 0) { "Execution counts cannot be negative" }
        require(initialSystemIndex in 0..1) { "initialSystemIndex must identify a system" }

        fun systemOrder(startOffset: Int): List<Int> =
            (0..1).map { (initialSystemIndex + startOffset + it) % 2 }

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
