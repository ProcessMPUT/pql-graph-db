package com.processm.processminterpreter.application.processm

import java.time.Duration
import java.time.Instant

/**
 * Compares two XES JSON structures (LOCAL vs REMOTE) for verification.
 *
 * Comparison model: strict ProcessM compatibility.
 *
 * Rules:
 * - Order-independent where the data gives us a stable key.
 * - `identity:id` is ignored universally because ProcessM assigns unrelated UUIDs.
 * - Ignored log/trace metadata is limited to the explicit key sets below.
 * - Any non-ignored attribute missing on either side is a mismatch.
 * - Values present on both sides but differing are flagged, with float tolerance.
 *
 * Practical effect: a query returns MATCH only when both systems expose the same
 * non-ignored XES content for the compared result.
 */
object XESJsonComparator {
    private val NOW_ATTRIBUTE = Regex("^(log|trace|event):now\\(\\)$")
    private val NOW_MAX_DRIFT: Duration = Duration.ofMinutes(5)

    private val IGNORED_KEYS = setOf("identity:id")

    private val IGNORED_LOG_KEYS =
        setOf(
            "identity:id",
            "source",
            "description",
            "lifecycle:model",
            "concept:name",
            "traceGlobals",
            "eventGlobals",
            "extensions",
        )

    private val IGNORED_TRACE_KEYS =
        setOf(
            "identity:id",
            "count(trace:concept:name)",
        )

    private val IGNORED_EVENT_KEYS =
        setOf(
            "identity:id",
        )

    fun compare(
        localJson: List<Map<String, Any?>>,
        remoteJson: List<Map<String, Any?>>,
    ): ComparisonResult {
        val diffs = mutableListOf<String>()

        val localLog = XesJsonTreeReader.readLog(localJson)
        val remoteLog = XesJsonTreeReader.readLog(remoteJson)

        if (localLog == null && remoteLog == null) {
            return ComparisonResult(true, 0, 0, emptyList(), "Both empty")
        }
        if (localLog == null) {
            return ComparisonResult(false, 0, 1, listOf("LOCAL has no log data"), "LOCAL empty")
        }
        if (remoteLog == null) {
            return ComparisonResult(false, 1, 0, listOf("REMOTE has no log data"), "REMOTE empty")
        }

        compareDuplicateAttributes("LOCAL Log", localLog, diffs, IGNORED_LOG_KEYS)
        compareDuplicateAttributes("REMOTE Log", remoteLog, diffs, IGNORED_LOG_KEYS)
        compareAttributes("Log", localLog.attributesByKey(), remoteLog.attributesByKey(), diffs, IGNORED_LOG_KEYS)

        val localTraces = localLog.traces
        val remoteTraces = remoteLog.traces

        if (localTraces.size != remoteTraces.size) {
            diffs.add("Trace count: LOCAL=${localTraces.size}, REMOTE=${remoteTraces.size}")
        }

        compareTraces(localTraces, remoteTraces, diffs)

        val match = diffs.isEmpty()
        val summary =
            if (match) {
                "MATCH: ${localTraces.size} traces, attributes identical"
            } else {
                "MISMATCH: ${diffs.size} difference(s) found"
            }

        return ComparisonResult(match, localTraces.size, remoteTraces.size, diffs, summary)
    }

    private fun compareTraces(
        localTraces: List<XesJsonNode>,
        remoteTraces: List<XesJsonNode>,
        diffs: MutableList<String>,
    ) {
        val localByName = groupByConceptName(localTraces)
        val remoteByName = groupByConceptName(remoteTraces)

        val allLocalUnknown = localByName.keys == setOf("unknown") && localTraces.size > 1
        val allRemoteUnknown = remoteByName.keys == setOf("unknown") && remoteTraces.size > 1
        val useFingerprintMatching = allLocalUnknown && allRemoteUnknown

        if (useFingerprintMatching) {
            compareFingerprintMatchedTraces(localTraces, remoteTraces, diffs)
        } else {
            compareNameMatchedTraces(localByName, remoteByName, diffs)
        }
    }

    private fun compareFingerprintMatchedTraces(
        localTraces: List<XesJsonNode>,
        remoteTraces: List<XesJsonNode>,
        diffs: MutableList<String>,
    ) {
        val sortedLocal = localTraces.sortedBy { traceFingerprint(it) }
        val sortedRemote = remoteTraces.sortedBy { traceFingerprint(it) }
        val count = maxOf(sortedLocal.size, sortedRemote.size)

        for (index in 0 until count) {
            val localTrace = sortedLocal.getOrNull(index)
            val remoteTrace = sortedRemote.getOrNull(index)
            val label = "[$index]"

            when {
                localTrace == null -> diffs.add("Trace $label: extra in REMOTE")
                remoteTrace == null -> diffs.add("Trace $label: extra in LOCAL")
                else -> compareTrace(label, localTrace, remoteTrace, diffs)
            }
        }
    }

    private fun compareNameMatchedTraces(
        localByName: Map<String, List<XesJsonNode>>,
        remoteByName: Map<String, List<XesJsonNode>>,
        diffs: MutableList<String>,
    ) {
        val missingInLocal = mutableListOf<String>()
        val missingInRemote = mutableListOf<String>()

        for (traceName in (localByName.keys + remoteByName.keys).toSortedSet()) {
            val localGroup = localByName[traceName] ?: emptyList()
            val remoteGroup = remoteByName[traceName] ?: emptyList()

            if (localGroup.isEmpty()) {
                missingInLocal.add(traceName)
                continue
            }
            if (remoteGroup.isEmpty()) {
                missingInRemote.add(traceName)
                continue
            }

            val count = maxOf(localGroup.size, remoteGroup.size)
            for (index in 0 until count) {
                val suffix = if (count > 1) " [$index]" else ""
                val localTrace = localGroup.getOrNull(index)
                val remoteTrace = remoteGroup.getOrNull(index)

                when {
                    localTrace == null -> diffs.add("Trace '$traceName'$suffix: extra in REMOTE")
                    remoteTrace == null -> diffs.add("Trace '$traceName'$suffix: extra in LOCAL")
                    else -> compareTrace(traceName, localTrace, remoteTrace, diffs)
                }
            }
        }

        missingInRemote.forEach { diffs.add("Trace '$it': missing in REMOTE") }
        missingInLocal.forEach { diffs.add("Trace '$it': missing in LOCAL") }
    }

    private fun compareTrace(
        traceName: String,
        localTrace: XesJsonNode,
        remoteTrace: XesJsonNode,
        diffs: MutableList<String>,
    ) {
        compareDuplicateAttributes("LOCAL Trace '$traceName'", localTrace, diffs, IGNORED_TRACE_KEYS)
        compareDuplicateAttributes("REMOTE Trace '$traceName'", remoteTrace, diffs, IGNORED_TRACE_KEYS)
        compareAttributes(
            "Trace '$traceName'",
            localTrace.attributesByKey(),
            remoteTrace.attributesByKey(),
            diffs,
            IGNORED_TRACE_KEYS,
        )

        if (localTrace.rawEventCount != remoteTrace.rawEventCount) {
            val localDesc = describeEventCount(localTrace)
            val remoteDesc = describeEventCount(remoteTrace)
            diffs.add("Trace '$traceName': event count LOCAL=$localDesc, REMOTE=$remoteDesc")
        }

        localTrace.events.forEachIndexed { index, event ->
            compareDuplicateAttributes("LOCAL Trace '$traceName' event[$index]", event, diffs, IGNORED_EVENT_KEYS)
        }
        remoteTrace.events.forEachIndexed { index, event ->
            compareDuplicateAttributes("REMOTE Trace '$traceName' event[$index]", event, diffs, IGNORED_EVENT_KEYS)
        }

        val localEventAttrs = localTrace.events.map { it.attributesByKey() }
        val remoteEventAttrs = remoteTrace.events.map { it.attributesByKey() }
        val useTimestampOnly = !hasNamedEvents(localEventAttrs) || !hasNamedEvents(remoteEventAttrs)

        compareEvents(
            traceName = traceName,
            localByKey = groupEventsByKey(localEventAttrs, useTimestampOnly),
            remoteByKey = groupEventsByKey(remoteEventAttrs, useTimestampOnly),
            diffs = diffs,
        )
    }

    private fun compareEvents(
        traceName: String,
        localByKey: Map<String, List<Map<String, String>>>,
        remoteByKey: Map<String, List<Map<String, String>>>,
        diffs: MutableList<String>,
    ) {
        val missingEventsInLocal = mutableListOf<String>()
        val missingEventsInRemote = mutableListOf<String>()

        for (eventKey in (localByKey.keys + remoteByKey.keys).toSortedSet()) {
            val localGroup = localByKey[eventKey] ?: emptyList()
            val remoteGroup = remoteByKey[eventKey] ?: emptyList()

            if (localGroup.isEmpty()) {
                missingEventsInLocal.add(eventKey)
                continue
            }
            if (remoteGroup.isEmpty()) {
                missingEventsInRemote.add(eventKey)
                continue
            }

            val sortedLocal = localGroup.sortedBy(::eventSortKey)
            val sortedRemote = remoteGroup.sortedBy(::eventSortKey)
            val count = maxOf(sortedLocal.size, sortedRemote.size)

            for (index in 0 until count) {
                val localEvent = sortedLocal.getOrNull(index)
                val remoteEvent = sortedRemote.getOrNull(index)

                when {
                    localEvent == null -> missingEventsInLocal.add("$eventKey (extra)")
                    remoteEvent == null -> missingEventsInRemote.add("$eventKey (extra)")
                    else -> compareAttributes(
                        "Trace '$traceName' event '$eventKey'",
                        localEvent,
                        remoteEvent,
                        diffs,
                        IGNORED_EVENT_KEYS,
                    )
                }
            }
        }

        missingEventsInRemote.forEach { diffs.add("Trace '$traceName' event '$it': missing in REMOTE") }
        missingEventsInLocal.forEach { diffs.add("Trace '$traceName' event '$it': missing in LOCAL") }
    }

    private fun compareAttributes(
        context: String,
        local: Map<String, String>,
        remote: Map<String, String>,
        diffs: MutableList<String>,
        ignoredKeys: Set<String> = IGNORED_KEYS,
    ) {
        val localFiltered = local.filterKeys { it !in ignoredKeys && !it.startsWith("meta_") }
        val remoteFiltered = remote.filterKeys { it !in ignoredKeys && !it.startsWith("meta_") }

        for ((key, localValue) in localFiltered) {
            if (key !in remoteFiltered) {
                diffs.add("$context: REMOTE missing '$key' (LOCAL='$localValue')")
            }
        }

        for ((key, remoteValue) in remoteFiltered) {
            val localValue = localFiltered[key]
            when {
                localValue == null -> diffs.add("$context: LOCAL missing '$key' (REMOTE='$remoteValue')")
                localValue != remoteValue &&
                    !equivalentNumbers(localValue, remoteValue) &&
                    !equivalentVolatileNow(key, localValue, remoteValue) ->
                    diffs.add("$context: '$key' differs LOCAL='$localValue' REMOTE='$remoteValue'")
            }
        }
    }

    private fun compareDuplicateAttributes(
        context: String,
        node: XesJsonNode,
        diffs: MutableList<String>,
        ignoredKeys: Set<String>,
    ) {
        val duplicates =
            node.attributeCounts()
                .filterKeys { it !in ignoredKeys && !it.startsWith("meta_") }
                .filterValues { it > 1 }

        for ((key, count) in duplicates) {
            diffs.add("$context: duplicate XES attribute '$key' appears $count times")
        }
    }

    private fun traceFingerprint(trace: XesJsonNode): String {
        val attrStr =
            trace.attributesByKey()
                .filterKeys { it !in IGNORED_TRACE_KEYS }
                .entries
                .sortedBy { it.key }
                .joinToString("|") { "${it.key}=${it.value}" }
        val eventStr =
            trace.events
                .map { event ->
                    event.attributesByKey()
                        .filterKeys { it !in IGNORED_EVENT_KEYS }
                        .entries
                        .sortedBy { it.key }
                        .joinToString(",") { "${it.key}=${it.value}" }
                }.sorted()
                .joinToString(";")
        return "$attrStr||rawEventCount=${trace.rawEventCount}||$eventStr"
    }

    private fun groupByConceptName(items: List<XesJsonNode>): Map<String, List<XesJsonNode>> =
        items.groupBy { node ->
            node.attributesByKey()["concept:name"] ?: "unknown"
        }

    private fun groupEventsByKey(
        events: List<Map<String, String>>,
        timestampOnly: Boolean = false,
    ): Map<String, List<Map<String, String>>> =
        events.groupBy { attrs ->
            val timestamp = attrs["time:timestamp"] ?: "?"
            if (timestampOnly) {
                timestamp
            } else {
                val name = attrs["concept:name"] ?: "?"
                "$timestamp|$name"
            }
        }

    private fun hasNamedEvents(events: List<Map<String, String>>): Boolean =
        events.any { (it["concept:name"] ?: "?") != "?" }

    private fun eventSortKey(event: Map<String, String>): String =
        listOf(
            event["lifecycle:transition"] ?: "",
            event["org:resource"] ?: "",
            event["result"] ?: "",
        ).joinToString("|")

    private fun describeEventCount(trace: XesJsonNode): String =
        if (trace.events.size != trace.rawEventCount) {
            "${trace.events.size} real + ${trace.rawEventCount - trace.events.size} null"
        } else {
            "${trace.rawEventCount}"
        }

    private fun equivalentNumbers(
        localValue: String,
        remoteValue: String,
    ): Boolean {
        val localNumber = localValue.toDoubleOrNull()
        val remoteNumber = remoteValue.toDoubleOrNull()
        return localNumber != null && remoteNumber != null && Math.abs(localNumber - remoteNumber) < 1e-6
    }

    private fun equivalentVolatileNow(
        key: String,
        localValue: String,
        remoteValue: String,
    ): Boolean {
        if (!NOW_ATTRIBUTE.matches(key)) return false
        val localInstant = runCatching { Instant.parse(localValue) }.getOrNull() ?: return false
        val remoteInstant = runCatching { Instant.parse(remoteValue) }.getOrNull() ?: return false
        return Duration.between(localInstant, remoteInstant).abs().compareTo(NOW_MAX_DRIFT) <= 0
    }
}

data class ComparisonResult(
    val match: Boolean,
    val traceCountLocal: Int,
    val traceCountRemote: Int,
    val differences: List<String>,
    val summary: String,
)
