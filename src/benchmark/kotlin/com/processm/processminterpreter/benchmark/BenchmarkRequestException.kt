package com.processm.processminterpreter.benchmark

/** Preserve a failed HTTP response as evidence instead of only truncating it in an exception. */
class BenchmarkRequestException(
    val statusCode: Int,
    val body: String,
    val startedAt: String,
    val startedNanos: Long,
    val finishedNanos: Long,
) : IllegalStateException("Query failed (HTTP $statusCode)")
