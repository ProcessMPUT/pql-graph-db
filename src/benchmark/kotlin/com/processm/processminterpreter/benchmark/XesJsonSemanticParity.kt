package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.processm.processminterpreter.processm.compat.XESJsonComparator

/** Untimed semantic parity check using the same strict comparator as compatibility reports. */
object XesJsonSemanticParity {
    private val mapper = jacksonObjectMapper()
    private val listType = object : TypeReference<List<Map<String, Any?>>>() {}

    data class Result(
        val matches: Boolean,
        val details: String,
    )

    fun compare(
        localBody: String,
        referenceBody: String,
    ): Result {
        val local = parse(localBody) ?: return Result(false, "LOCAL response is not an XES-JSON array")
        val reference = parse(referenceBody) ?: return Result(false, "REFERENCE response is not an XES-JSON array")
        val comparison = XESJsonComparator.compare(local, reference)
        if (comparison.match) return Result(true, comparison.summary)
        val examples = comparison.differences.take(3).joinToString(" | ")
        return Result(
            matches = false,
            details = buildString {
                append(comparison.summary)
                if (examples.isNotBlank()) append(": $examples")
            },
        )
    }

    private fun parse(body: String): List<Map<String, Any?>>? =
        runCatching { mapper.readValue(body, listType) }.getOrNull()
}
