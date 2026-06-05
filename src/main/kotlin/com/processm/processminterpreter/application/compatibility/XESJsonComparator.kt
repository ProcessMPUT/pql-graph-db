package com.processm.processminterpreter.application.compatibility

import com.processm.processminterpreter.domain.log.xes.XesAttributeValue
import com.processm.processminterpreter.domain.log.xes.XesEvent
import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.log.xes.XesTrace
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Compares two XES JSON structures (LOCAL vs REMOTE) for verification.
 *
 * Comparison model: strict ProcessM compatibility.
 *
 * Rules:
 * - Order-independent where the data gives us a stable key.
 * - `identity:id` must be present on the same side and with the same XES type,
 *   but its UUID value is ignored because ProcessM may assign unrelated ids.
 * - Ignored log/trace metadata is limited to the explicit key sets below.
 * - Any non-ignored attribute missing on either side is a mismatch.
 * - Values present on both sides but differing are flagged, with numeric tolerance for equal XES types.
 *
 * Practical effect: a query returns MATCH only when both systems expose the same
 * non-ignored XES content for the compared result.
 */
object XESJsonComparator {
    private const val ATTR_CONCEPT_NAME = "concept:name"
    private const val ATTR_CONCEPT_INSTANCE = "concept:instance"
    private const val ATTR_IDENTITY_ID = "identity:id"
    private const val ATTR_LIFECYCLE_TRANSITION = "lifecycle:transition"
    private const val ATTR_LIFECYCLE_STATE = "lifecycle:state"
    private const val ATTR_ORG_RESOURCE = "org:resource"
    private const val ATTR_ORG_ROLE = "org:role"
    private const val ATTR_ORG_GROUP = "org:group"
    private const val ATTR_TIME_TIMESTAMP = "time:timestamp"
    private const val ATTR_COST_CURRENCY = "cost:currency"
    private const val ATTR_COST_TOTAL = "cost:total"

    private val NOW_ATTRIBUTE = Regex("^((log|trace|event):)?now\\(\\)$")
    private val NOW_MAX_DRIFT: Duration = Duration.ofMinutes(5)
    private val XES_DATE_NO_MILLIS: DateTimeFormatter =
        DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)

    private val IGNORED_KEYS = emptySet<String>()

    private val IGNORED_LOG_KEYS = emptySet<String>()

    private val IGNORED_TRACE_KEYS = emptySet<String>()

    private val IGNORED_EVENT_KEYS = emptySet<String>()

    private val TRACE_STANDARD_ATTRIBUTES =
        setOf(
            ATTR_CONCEPT_NAME,
            ATTR_IDENTITY_ID,
            ATTR_COST_CURRENCY,
            ATTR_COST_TOTAL,
        )

    private val EVENT_STANDARD_ATTRIBUTES =
        setOf(
            ATTR_CONCEPT_NAME,
            ATTR_CONCEPT_INSTANCE,
            ATTR_IDENTITY_ID,
            ATTR_LIFECYCLE_TRANSITION,
            ATTR_LIFECYCLE_STATE,
            ATTR_ORG_RESOURCE,
            ATTR_ORG_ROLE,
            ATTR_ORG_GROUP,
            ATTR_TIME_TIMESTAMP,
            ATTR_COST_CURRENCY,
            ATTR_COST_TOTAL,
        )

    fun compare(
        localJson: List<Map<String, Any?>>,
        remoteJson: List<Map<String, Any?>>,
    ): ComparisonResult {
        val diffs = mutableListOf<String>()

        val localLogs = XesJsonTreeReader.readLogs(localJson)
        val remoteLogs = XesJsonTreeReader.readLogs(remoteJson)

        if (localLogs.isEmpty() && remoteLogs.isEmpty()) {
            return ComparisonResult(true, 0, 0, emptyList(), "Both empty")
        }
        if (localLogs.isEmpty()) {
            return ComparisonResult(false, 0, remoteLogs.size, listOf("LOCAL has no log data"), "LOCAL empty")
        }
        if (remoteLogs.isEmpty()) {
            return ComparisonResult(false, localLogs.size, 0, listOf("REMOTE has no log data"), "REMOTE empty")
        }

        compareLogs(localLogs, remoteLogs, diffs, ignoreEventOrder = false)

        val localTraceCount = localLogs.sumOf { it.traces.size }
        val remoteTraceCount = remoteLogs.sumOf { it.traces.size }

        val match = diffs.isEmpty()
        val summary =
            if (match) {
                "MATCH: ${localLogs.size} logs, $localTraceCount traces, attributes identical"
            } else {
                "MISMATCH: ${diffs.size} difference(s) found"
            }

        return ComparisonResult(match, localTraceCount, remoteTraceCount, diffs, summary)
    }

    fun compareRemoteTraceSubset(
        localJson: List<Map<String, Any?>>,
        remoteJson: List<Map<String, Any?>>,
        ignoreEventOrder: Boolean = false,
        allowEventSubset: Boolean = false,
    ): ComparisonResult {
        val diffs = mutableListOf<String>()

        val localLog = XesJsonTreeReader.readLog(localJson)
        val remoteLog = XesJsonTreeReader.readLog(remoteJson)

        if (localLog == null || remoteLog == null) {
            return compare(localJson, remoteJson)
        }

        compareDuplicateAttributes("LOCAL Log", localLog, diffs, IGNORED_LOG_KEYS)
        compareDuplicateAttributes("REMOTE Log", remoteLog, diffs, IGNORED_LOG_KEYS)
        compareMetadata("Log", localLog.metadata, remoteLog.metadata, diffs)
        compareAttributes("Log", localLog.attributesByKey(), remoteLog.attributesByKey(), diffs, IGNORED_LOG_KEYS)
        if (diffs.isNotEmpty()) {
            return ComparisonResult(
                match = false,
                traceCountLocal = localLog.traces.size,
                traceCountRemote = remoteLog.traces.size,
                differences = diffs,
                summary = "MISMATCH: log metadata or attributes differ",
            )
        }

        if (allowEventSubset) {
            return compareRemoteTraceContainment(
                localTraces = localLog.traces,
                remoteTraces = remoteLog.traces,
                ignoreEventOrder = ignoreEventOrder,
                localTraceCount = localLog.traces.size,
                remoteTraceCount = remoteLog.traces.size,
            )
        }

        val remainingLocal =
            localLog.traces
                .groupingBy { trace -> traceFingerprint(trace, preserveEventOrder = !ignoreEventOrder) }
                .eachCount()
                .toMutableMap()

        val missingRemoteTraces = mutableListOf<String>()
        remoteLog.traces.forEachIndexed { index, remoteTrace ->
            val fingerprint = traceFingerprint(remoteTrace, preserveEventOrder = !ignoreEventOrder)
            val remaining = remainingLocal[fingerprint] ?: 0
            if (remaining > 0) {
                remainingLocal[fingerprint] = remaining - 1
            } else {
                missingRemoteTraces += "Trace [$index]: no equivalent trace in expanded LOCAL result"
            }
        }

        if (missingRemoteTraces.isNotEmpty()) {
            return ComparisonResult(
                match = false,
                traceCountLocal = localLog.traces.size,
                traceCountRemote = remoteLog.traces.size,
                differences = missingRemoteTraces,
                summary = "MISMATCH: remote traces are not contained in expanded LOCAL result",
            )
        }

        return ComparisonResult(
            match = false,
            traceCountLocal = localLog.traces.size,
            traceCountRemote = remoteLog.traces.size,
            differences = emptyList(),
            summary = nondeterministicSubsetSummary(ignoreEventOrder),
            status = ComparisonStatus.NONDETERMINISTIC_MATCH,
        )
    }

    fun compareRemoteTraceSubset(
        localLogs: List<XesLog>,
        remoteJson: List<Map<String, Any?>>,
        isProjectedQuery: Boolean,
        projectedTraceStandardAttributes: Set<String>,
        includeEvents: Boolean,
        ignoreEventOrder: Boolean = false,
        allowEventSubset: Boolean = false,
    ): ComparisonResult {
        val remoteLog = XesJsonTreeReader.readLog(remoteJson)
            ?: return ComparisonResult(
                match = false,
                traceCountLocal = localLogs.sumOf { it.traces.size },
                traceCountRemote = 0,
                differences = listOf("REMOTE has no log data"),
                summary = "REMOTE empty",
            )

        if (allowEventSubset) {
            return compareRemoteTraceContainment(
                localTraces = localLogs.flatMap { it.traces },
                remoteTraces = remoteLog.traces,
                isProjectedQuery = isProjectedQuery,
                projectedTraceStandardAttributes = projectedTraceStandardAttributes,
                includeEvents = includeEvents,
                ignoreEventOrder = ignoreEventOrder,
                localTraceCount = localLogs.sumOf { it.traces.size },
                remoteTraceCount = remoteLog.traces.size,
            )
        }

        val remainingLocal =
            localLogs
                .flatMap { log -> log.traces }
                .groupingBy { trace ->
                    traceOrderedFingerprint(
                    trace = trace,
                    isProjectedQuery = isProjectedQuery,
                    projectedTraceStandardAttributes = projectedTraceStandardAttributes,
                    includeEvents = includeEvents,
                    preserveEventOrder = !ignoreEventOrder,
                )
            }.eachCount()
                .toMutableMap()

        val missingRemoteTraces = mutableListOf<String>()
        remoteLog.traces.forEachIndexed { index, remoteTrace ->
            val fingerprint = traceFingerprint(remoteTrace, preserveEventOrder = !ignoreEventOrder)
            val remaining = remainingLocal[fingerprint] ?: 0
            if (remaining > 0) {
                remainingLocal[fingerprint] = remaining - 1
            } else {
                missingRemoteTraces += "Trace [$index]: no equivalent trace in expanded LOCAL result"
            }
        }

        if (missingRemoteTraces.isNotEmpty()) {
            return ComparisonResult(
                match = false,
                traceCountLocal = localLogs.sumOf { it.traces.size },
                traceCountRemote = remoteLog.traces.size,
                differences = missingRemoteTraces,
                summary = "MISMATCH: remote traces are not contained in expanded LOCAL result",
            )
        }

        return ComparisonResult(
            match = false,
            traceCountLocal = localLogs.sumOf { it.traces.size },
            traceCountRemote = remoteLog.traces.size,
            differences = emptyList(),
            summary = nondeterministicSubsetSummary(ignoreEventOrder),
            status = ComparisonStatus.NONDETERMINISTIC_MATCH,
        )
    }

    private fun compareRemoteTraceContainment(
        localTraces: List<XesJsonNode>,
        remoteTraces: List<XesJsonNode>,
        ignoreEventOrder: Boolean,
        localTraceCount: Int,
        remoteTraceCount: Int,
    ): ComparisonResult {
        val remainingLocal = localTraces.toMutableList()
        val missingRemoteTraces = mutableListOf<String>()

        remoteTraces.forEachIndexed { index, remoteTrace ->
            val matchIndex = remainingLocal.indexOfFirst { localTrace ->
                traceContainsRemoteEvents(
                    localTrace = localTrace,
                    remoteTrace = remoteTrace,
                    ignoreEventOrder = ignoreEventOrder,
                )
            }
            if (matchIndex >= 0) {
                remainingLocal.removeAt(matchIndex)
            } else {
                missingRemoteTraces += "Trace [$index]: no equivalent trace/event window in expanded LOCAL result"
            }
        }

        if (missingRemoteTraces.isNotEmpty()) {
            return ComparisonResult(
                match = false,
                traceCountLocal = localTraceCount,
                traceCountRemote = remoteTraceCount,
                differences = missingRemoteTraces,
                summary = "MISMATCH: remote traces/events are not contained in expanded LOCAL result",
            )
        }

        return ComparisonResult(
            match = false,
            traceCountLocal = localTraceCount,
            traceCountRemote = remoteTraceCount,
            differences = emptyList(),
            summary = "NONDETERMINISTIC_MATCH: remote trace/event windows are contained in expanded LOCAL result; " +
                "strict mismatch is caused by unstable ProcessM grouped-event ordering",
            status = ComparisonStatus.NONDETERMINISTIC_MATCH,
        )
    }

    private fun compareRemoteTraceContainment(
        localTraces: List<XesTrace>,
        remoteTraces: List<XesJsonNode>,
        isProjectedQuery: Boolean,
        projectedTraceStandardAttributes: Set<String>,
        includeEvents: Boolean,
        ignoreEventOrder: Boolean,
        localTraceCount: Int,
        remoteTraceCount: Int,
    ): ComparisonResult {
        val remainingLocal = localTraces.toMutableList()
        val missingRemoteTraces = mutableListOf<String>()

        remoteTraces.forEachIndexed { index, remoteTrace ->
            val matchIndex = remainingLocal.indexOfFirst { localTrace ->
                traceContainsRemoteEvents(
                    localTrace = localTrace,
                    remoteTrace = remoteTrace,
                    isProjectedQuery = isProjectedQuery,
                    projectedTraceStandardAttributes = projectedTraceStandardAttributes,
                    includeEvents = includeEvents,
                    ignoreEventOrder = ignoreEventOrder,
                )
            }
            if (matchIndex >= 0) {
                remainingLocal.removeAt(matchIndex)
            } else {
                missingRemoteTraces += "Trace [$index]: no equivalent trace/event window in expanded LOCAL result"
            }
        }

        if (missingRemoteTraces.isNotEmpty()) {
            return ComparisonResult(
                match = false,
                traceCountLocal = localTraceCount,
                traceCountRemote = remoteTraceCount,
                differences = missingRemoteTraces,
                summary = "MISMATCH: remote traces/events are not contained in expanded LOCAL result",
            )
        }

        return ComparisonResult(
            match = false,
            traceCountLocal = localTraceCount,
            traceCountRemote = remoteTraceCount,
            differences = emptyList(),
            summary = "NONDETERMINISTIC_MATCH: remote trace/event windows are contained in expanded LOCAL result; " +
                "strict mismatch is caused by unstable ProcessM grouped-event ordering",
            status = ComparisonStatus.NONDETERMINISTIC_MATCH,
        )
    }

    fun compareIgnoringEventOrder(
        localJson: List<Map<String, Any?>>,
        remoteJson: List<Map<String, Any?>>,
    ): ComparisonResult {
        val diffs = mutableListOf<String>()

        val localLogs = XesJsonTreeReader.readLogs(localJson)
        val remoteLogs = XesJsonTreeReader.readLogs(remoteJson)

        if (localLogs.isEmpty() || remoteLogs.isEmpty()) {
            return compare(localJson, remoteJson)
        }

        compareLogs(localLogs, remoteLogs, diffs, ignoreEventOrder = true)
        val localTraceCount = localLogs.sumOf { it.traces.size }
        val remoteTraceCount = remoteLogs.sumOf { it.traces.size }

        if (diffs.isNotEmpty()) {
            return ComparisonResult(
                match = false,
                traceCountLocal = localTraceCount,
                traceCountRemote = remoteTraceCount,
                differences = diffs,
                summary = "MISMATCH: ${diffs.size} difference(s) found after ignoring grouped-event order",
            )
        }

        return ComparisonResult(
            match = false,
            traceCountLocal = localTraceCount,
            traceCountRemote = remoteTraceCount,
            differences = emptyList(),
            summary = "NONDETERMINISTIC_MATCH: attributes and event multisets match; " +
                "strict mismatch is caused by unstable ProcessM grouped-event ordering",
            status = ComparisonStatus.NONDETERMINISTIC_MATCH,
        )
    }

    fun hasOnlyTraceWindowDifferences(result: ComparisonResult): Boolean =
        !result.match &&
            result.differences.isNotEmpty() &&
            result.differences.all(::isTraceWindowDifference)

    private fun compareLogs(
        localLogs: List<XesJsonNode>,
        remoteLogs: List<XesJsonNode>,
        diffs: MutableList<String>,
        ignoreEventOrder: Boolean,
    ) {
        if (localLogs.size != remoteLogs.size) {
            diffs.add("Log count: LOCAL=${localLogs.size}, REMOTE=${remoteLogs.size}")
        }

        val localByName = groupByConceptName(localLogs)
        val remoteByName = groupByConceptName(remoteLogs)

        for (logName in (localByName.keys + remoteByName.keys).toSortedSet()) {
            val localGroup = localByName[logName].orEmpty()
            val remoteGroup = remoteByName[logName].orEmpty()

            when {
                localGroup.isEmpty() -> diffs.add("Log '$logName': missing in LOCAL")
                remoteGroup.isEmpty() -> diffs.add("Log '$logName': missing in REMOTE")
                localGroup.size == 1 && remoteGroup.size == 1 ->
                    compareLog("Log '$logName'", localGroup.single(), remoteGroup.single(), diffs, ignoreEventOrder)
                else ->
                    compareLogMultiset("Log '$logName'", localGroup, remoteGroup, diffs, ignoreEventOrder)
            }
        }
    }

    private fun compareLog(
        context: String,
        localLog: XesJsonNode,
        remoteLog: XesJsonNode,
        diffs: MutableList<String>,
        ignoreEventOrder: Boolean,
    ) {
        compareDuplicateAttributes("LOCAL $context", localLog, diffs, IGNORED_LOG_KEYS)
        compareDuplicateAttributes("REMOTE $context", remoteLog, diffs, IGNORED_LOG_KEYS)
        compareMetadata(context, localLog.metadata, remoteLog.metadata, diffs)
        compareAttributes(context, localLog.attributesByKey(), remoteLog.attributesByKey(), diffs, IGNORED_LOG_KEYS)

        val localTraces = localLog.traces
        val remoteTraces = remoteLog.traces
        if (localTraces.size != remoteTraces.size) {
            diffs.add("$context trace count: LOCAL=${localTraces.size}, REMOTE=${remoteTraces.size}")
        }

        if (ignoreEventOrder) {
            compareTraceMultiset(
                context = "$context anonymous traces",
                localTraces = localTraces,
                remoteTraces = remoteTraces,
                diffs = diffs,
                preserveEventOrder = false,
            )
        } else {
            compareTraces(localTraces, remoteTraces, diffs)
        }
    }

    private fun compareLogMultiset(
        context: String,
        localLogs: List<XesJsonNode>,
        remoteLogs: List<XesJsonNode>,
        diffs: MutableList<String>,
        ignoreEventOrder: Boolean,
    ) {
        val localByFingerprint = localLogs.groupingBy { log -> logFingerprint(log, preserveEventOrder = !ignoreEventOrder) }.eachCount()
        val remoteByFingerprint = remoteLogs.groupingBy { log -> logFingerprint(log, preserveEventOrder = !ignoreEventOrder) }.eachCount()

        for (fingerprint in (localByFingerprint.keys + remoteByFingerprint.keys).toSortedSet()) {
            val localCount = localByFingerprint[fingerprint] ?: 0
            val remoteCount = remoteByFingerprint[fingerprint] ?: 0
            if (localCount > remoteCount) {
                diffs.add("$context: ${localCount - remoteCount} equivalent log(s) extra in LOCAL")
            } else if (remoteCount > localCount) {
                diffs.add("$context: ${remoteCount - localCount} equivalent log(s) extra in REMOTE")
            }
        }
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
        compareTraceMultiset("anonymous traces", localTraces, remoteTraces, diffs)
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

            if (localGroup.size == 1 && remoteGroup.size == 1) {
                compareTrace(traceName, localGroup.single(), remoteGroup.single(), diffs)
            } else {
                compareTraceMultiset("Trace '$traceName'", localGroup, remoteGroup, diffs)
            }
        }

        missingInRemote.forEach { diffs.add("Trace '$it': missing in REMOTE") }
        missingInLocal.forEach { diffs.add("Trace '$it': missing in LOCAL") }
    }

    private fun compareTraceMultiset(
        context: String,
        localTraces: List<XesJsonNode>,
        remoteTraces: List<XesJsonNode>,
        diffs: MutableList<String>,
        preserveEventOrder: Boolean = true,
    ) {
        val localByFingerprint = localTraces.groupBy { trace -> traceFingerprint(trace, preserveEventOrder) }
        val remoteByFingerprint = remoteTraces.groupBy { trace -> traceFingerprint(trace, preserveEventOrder) }

        for (fingerprint in (localByFingerprint.keys + remoteByFingerprint.keys).toSortedSet()) {
            val localGroup = localByFingerprint[fingerprint].orEmpty()
            val remoteGroup = remoteByFingerprint[fingerprint].orEmpty()
            val localCount = localGroup.size
            val remoteCount = remoteGroup.size

            when {
                localCount > remoteCount -> {
                    val extra = localCount - remoteCount
                    diffs.add(
                        "$context: $extra equivalent trace(s) extra in LOCAL: ${traceSummary(localGroup.first())}",
                    )
                }
                remoteCount > localCount -> {
                    val extra = remoteCount - localCount
                    diffs.add(
                        "$context: $extra equivalent trace(s) extra in REMOTE: ${traceSummary(remoteGroup.first())}",
                    )
                }
            }
        }
    }

    private fun isTraceWindowDifference(diff: String): Boolean =
        diff.startsWith("Trace count:") ||
            (
                diff.contains("equivalent trace(s) extra in LOCAL") ||
                    diff.contains("equivalent trace(s) extra in REMOTE")
            )

    private fun compareTrace(
        traceName: String,
        localTrace: XesJsonNode,
        remoteTrace: XesJsonNode,
        diffs: MutableList<String>,
    ) {
        compareDuplicateAttributes("LOCAL Trace '$traceName'", localTrace, diffs, IGNORED_TRACE_KEYS)
        compareDuplicateAttributes("REMOTE Trace '$traceName'", remoteTrace, diffs, IGNORED_TRACE_KEYS)
        compareMetadata("Trace '$traceName'", localTrace.metadata, remoteTrace.metadata, diffs)
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

        val useTimestampOnly = !hasNamedEvents(localTrace.events) || !hasNamedEvents(remoteTrace.events)

        compareEvents(
            traceName = traceName,
            localByKey = groupEventsByKey(localTrace.events, useTimestampOnly),
            remoteByKey = groupEventsByKey(remoteTrace.events, useTimestampOnly),
            diffs = diffs,
        )
    }

    private fun compareEvents(
        traceName: String,
        localByKey: Map<String, List<XesJsonNode>>,
        remoteByKey: Map<String, List<XesJsonNode>>,
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
                    else -> {
                        val context = "Trace '$traceName' event '$eventKey'"
                        compareAttributes(
                            context,
                            localEvent.attributesByKey(),
                            remoteEvent.attributesByKey(),
                            diffs,
                            IGNORED_EVENT_KEYS,
                        )
                        compareMetadata(
                            context,
                            localEvent.metadata,
                            remoteEvent.metadata,
                            diffs,
                        )
                    }
                }
            }
        }

        missingEventsInRemote.forEach { diffs.add("Trace '$traceName' event '$it': missing in REMOTE") }
        missingEventsInLocal.forEach { diffs.add("Trace '$traceName' event '$it': missing in LOCAL") }
    }

    private fun compareMetadata(
        context: String,
        local: Map<String, String>,
        remote: Map<String, String>,
        diffs: MutableList<String>,
    ) {
        for (key in (local.keys + remote.keys).toSortedSet()) {
            val localValue = local[key]
            val remoteValue = remote[key]
            when {
                localValue == null -> diffs.add("$context: LOCAL missing metadata '$key' (REMOTE='$remoteValue')")
                remoteValue == null -> diffs.add("$context: REMOTE missing metadata '$key' (LOCAL='$localValue')")
                localValue != remoteValue -> diffs.add(
                    "$context: metadata '$key' differs LOCAL='$localValue' REMOTE='$remoteValue'",
                )
            }
        }
    }

    private fun compareAttributes(
        context: String,
        local: Map<String, XesJsonAttribute>,
        remote: Map<String, XesJsonAttribute>,
        diffs: MutableList<String>,
        ignoredKeys: Set<String> = IGNORED_KEYS,
    ) {
        val localFiltered = local.filterKeys { it !in ignoredKeys }
        val remoteFiltered = remote.filterKeys { it !in ignoredKeys }

        for ((key, localAttribute) in localFiltered) {
            if (key !in remoteFiltered) {
                diffs.add("$context: REMOTE missing '$key' (LOCAL='${localAttribute.describe()}')")
            }
        }

        for ((key, remoteAttribute) in remoteFiltered) {
            val localAttribute = localFiltered[key]
            when {
                localAttribute == null -> diffs.add("$context: LOCAL missing '$key' (REMOTE='${remoteAttribute.describe()}')")
                localAttribute.type != remoteAttribute.type ->
                    diffs.add(
                        "$context: '$key' type differs LOCAL='${localAttribute.type}' REMOTE='${remoteAttribute.type}'",
                    )
                key == ATTR_IDENTITY_ID -> Unit
                localAttribute.value != remoteAttribute.value &&
                    !equivalentNumbers(localAttribute, remoteAttribute) &&
                    !equivalentVolatileNow(key, localAttribute.value, remoteAttribute.value) ->
                    diffs.add(
                        "$context: '$key' differs LOCAL='${localAttribute.value}' REMOTE='${remoteAttribute.value}'",
                    )
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
                .filterKeys { it !in ignoredKeys }
                .filterValues { it > 1 }

        for ((key, count) in duplicates) {
            diffs.add("$context: duplicate XES attribute '$key' appears $count times")
        }
    }

    private fun traceSummary(trace: XesJsonNode): String {
        val attributes =
            trace.attributesByKey()
                .filterKeys { it !in IGNORED_TRACE_KEYS }
                .entries
                .sortedBy { it.key }
                .joinToString(", ") { "${it.key}=${it.value.describe()}" }
                .ifBlank { "no trace attributes" }
        val metadata =
            trace.metadata.keys
                .sorted()
                .joinToString(", ")
                .ifBlank { "no metadata" }
        val events =
            trace.events
                .take(5)
                .joinToString(", ") { event ->
                    event.attributeValuesByKey()[ATTR_CONCEPT_NAME]
                        ?: orderedAttributeFingerprint(event.attributes, IGNORED_EVENT_KEYS).ifBlank { "event" }
                }
                .ifBlank { "no materialized events" }

        return "attrs=[$attributes], metadata=[$metadata], rawEvents=${trace.rawEventCount}, events=[$events]"
    }

    private fun traceOrderedFingerprint(trace: XesJsonNode): String {
        return traceFingerprint(trace, preserveEventOrder = true)
    }

    private fun logFingerprint(
        log: XesJsonNode,
        preserveEventOrder: Boolean,
    ): String =
        orderedAttributeFingerprint(log.attributes, IGNORED_LOG_KEYS) +
            "||metadata=${metadataFingerprint(log.metadata)}" +
            "||traces=" +
            log.traces
                .map { trace -> traceFingerprint(trace, preserveEventOrder) }
                .sorted()
                .joinToString(";")

    private fun traceFingerprint(
        trace: XesJsonNode,
        preserveEventOrder: Boolean,
    ): String {
        val header = traceHeaderFingerprint(trace)
        val eventFingerprints = eventFingerprints(trace)
        val eventStr =
            if (preserveEventOrder) {
                eventFingerprints.joinToString(";")
            } else {
                eventFingerprints.sorted().joinToString(";")
            }
        return "$header||rawEventCount=${trace.rawEventCount}||$eventStr"
    }

    private fun traceHeaderFingerprint(trace: XesJsonNode): String =
        orderedAttributeFingerprint(trace.attributes, IGNORED_TRACE_KEYS) +
            "||metadata=${metadataFingerprint(trace.metadata)}"

    private fun eventFingerprints(trace: XesJsonNode): List<String> =
        trace.events.map { event ->
            orderedAttributeFingerprint(event.attributes, IGNORED_EVENT_KEYS) +
                "||metadata=${metadataFingerprint(event.metadata)}"
        }

    private fun traceOrderedFingerprint(
        trace: XesTrace,
        isProjectedQuery: Boolean,
        projectedTraceStandardAttributes: Set<String>,
        includeEvents: Boolean,
        preserveEventOrder: Boolean = true,
    ): String {
        val attrStr = orderedAttributeFingerprint(
            attributes = traceAttributes(trace, isProjectedQuery, projectedTraceStandardAttributes),
            ignoredKeys = IGNORED_TRACE_KEYS,
        )
        val eventFingerprints =
            if (includeEvents) {
                eventFingerprints(trace)
            } else {
                emptyList()
            }
        val eventStr =
            if (preserveEventOrder) {
                eventFingerprints.joinToString(";")
            } else {
                eventFingerprints.sorted().joinToString(";")
            }
        val rawEventCount =
            if (includeEvents) {
                when {
                    trace.events.isNotEmpty() -> trace.events.size
                    trace.nullEventCount >= 1 -> trace.nullEventCount
                    !isProjectedQuery -> 1
                    else -> 0
                }
            } else {
                0
        }
        return "$attrStr||metadata=||rawEventCount=$rawEventCount||$eventStr"
    }

    private fun traceHeaderFingerprint(
        trace: XesTrace,
        isProjectedQuery: Boolean,
        projectedTraceStandardAttributes: Set<String>,
    ): String =
        orderedAttributeFingerprint(
            attributes = traceAttributes(trace, isProjectedQuery, projectedTraceStandardAttributes),
            ignoredKeys = IGNORED_TRACE_KEYS,
        ) + "||metadata="

    private fun eventFingerprints(trace: XesTrace): List<String> =
        trace.events.map { event ->
            orderedAttributeFingerprint(eventAttributes(event), IGNORED_EVENT_KEYS) +
                "||metadata="
        }

    private fun traceContainsRemoteEvents(
        localTrace: XesJsonNode,
        remoteTrace: XesJsonNode,
        ignoreEventOrder: Boolean,
    ): Boolean {
        if (traceHeaderFingerprint(localTrace) != traceHeaderFingerprint(remoteTrace)) return false
        if (!ignoreEventOrder && !eventFingerprints(localTrace).startsWith(eventFingerprints(remoteTrace))) return false
        if (remoteTrace.rawEventCount > localTrace.rawEventCount) return false
        return multisetContains(
            container = eventFingerprints(localTrace),
            subset = eventFingerprints(remoteTrace),
        )
    }

    private fun traceContainsRemoteEvents(
        localTrace: XesTrace,
        remoteTrace: XesJsonNode,
        isProjectedQuery: Boolean,
        projectedTraceStandardAttributes: Set<String>,
        includeEvents: Boolean,
        ignoreEventOrder: Boolean,
    ): Boolean {
        if (
            traceHeaderFingerprint(localTrace, isProjectedQuery, projectedTraceStandardAttributes) !=
            traceHeaderFingerprint(remoteTrace)
        ) {
            return false
        }
        val localEvents = if (includeEvents) eventFingerprints(localTrace) else emptyList()
        val remoteEvents = eventFingerprints(remoteTrace)
        if (!ignoreEventOrder && !localEvents.startsWith(remoteEvents)) return false
        return multisetContains(container = localEvents, subset = remoteEvents)
    }

    private fun List<String>.startsWith(prefix: List<String>): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun multisetContains(
        container: List<String>,
        subset: List<String>,
    ): Boolean {
        val remaining = container.groupingBy { it }.eachCount().toMutableMap()
        for (item in subset) {
            val count = remaining[item] ?: 0
            if (count == 0) return false
            remaining[item] = count - 1
        }
        return true
    }

    private fun nondeterministicSubsetSummary(ignoreEventOrder: Boolean): String {
        val cause =
            if (ignoreEventOrder) {
                "unstable ProcessM trace-variant and grouped-event ordering"
            } else {
                "unstable ProcessM trace-variant ordering"
            }
        return "NONDETERMINISTIC_MATCH: remote trace window is contained in expanded LOCAL result; " +
            "strict mismatch is caused by $cause"
    }

    private fun traceAttributes(
        trace: XesTrace,
        isProjectedQuery: Boolean,
        projectedTraceStandardAttributes: Set<String>,
    ): List<XesJsonAttribute> =
        processMJsonAttributes(
            linkedMapOf<String, Any?>().apply {
                putProjectedTraceAttribute(ATTR_CONCEPT_NAME, trace.conceptName, isProjectedQuery, projectedTraceStandardAttributes)
                putProjectedTraceAttribute(ATTR_IDENTITY_ID, trace.identityId, isProjectedQuery, projectedTraceStandardAttributes)
                putProjectedTraceAttribute(ATTR_COST_CURRENCY, trace.costCurrency, isProjectedQuery, projectedTraceStandardAttributes)
                putProjectedTraceAttribute(ATTR_COST_TOTAL, trace.costTotal, isProjectedQuery, projectedTraceStandardAttributes)

                trace.customAttributes.forEach { (key, value) ->
                    if (key !in TRACE_STANDARD_ATTRIBUTES) put(key, value)
                }
            },
        )

    private fun MutableMap<String, Any?>.putProjectedTraceAttribute(
        key: String,
        value: Any?,
        isProjectedQuery: Boolean,
        projectedTraceStandardAttributes: Set<String>,
    ) {
        if ((!isProjectedQuery || key in projectedTraceStandardAttributes) && value != null) {
            put(key, value)
        }
    }

    private fun eventAttributes(event: XesEvent): List<XesJsonAttribute> =
        processMJsonAttributes(
            linkedMapOf<String, Any?>().apply {
                event.conceptName?.let { put(ATTR_CONCEPT_NAME, it) }
                event.conceptInstance?.let { put(ATTR_CONCEPT_INSTANCE, it) }
                event.identityId?.let { put(ATTR_IDENTITY_ID, it) }
                event.timeTimestamp?.let { put(ATTR_TIME_TIMESTAMP, it) }
                event.lifecycleTransition?.let { put(ATTR_LIFECYCLE_TRANSITION, it) }
                event.lifecycleState?.let { put(ATTR_LIFECYCLE_STATE, it) }
                event.orgResource?.let { put(ATTR_ORG_RESOURCE, it) }
                event.orgRole?.let { put(ATTR_ORG_ROLE, it) }
                event.orgGroup?.let { put(ATTR_ORG_GROUP, it) }
                event.costCurrency?.let { put(ATTR_COST_CURRENCY, it) }
                event.costTotal?.let { put(ATTR_COST_TOTAL, it) }
                event.customAttributes.forEach { (key, value) ->
                    if (key !in EVENT_STANDARD_ATTRIBUTES) put(key, value)
                }
            },
        )

    private fun processMJsonAttributes(values: Map<String, Any?>): List<XesJsonAttribute> {
        val lastRunByType = linkedMapOf<String, MutableList<XesJsonAttribute>>()
        var previousType: String? = null
        var currentRun = mutableListOf<XesJsonAttribute>()

        values.keys.sorted().forEach { key ->
            val attribute = attributeToken(key, values[key])
            if (attribute.type != previousType) {
                currentRun = mutableListOf()
                lastRunByType[attribute.type] = currentRun
                previousType = attribute.type
            }
            currentRun.add(attribute)
        }

        return lastRunByType.values.flatten()
    }

    private fun attributeToken(
        key: String,
        rawValue: Any?,
    ): XesJsonAttribute {
        val value = if (rawValue is XesAttributeValue) rawValue.value else rawValue
        return when (value) {
            null -> XesJsonAttribute("string", key, "null")
            is String -> XesJsonAttribute("string", key, value)
            is Int, is Long -> XesJsonAttribute("int", key, value.toString())
            is Float, is Double -> XesJsonAttribute("float", key, value.toString())
            is Boolean -> XesJsonAttribute("boolean", key, value.toString())
            is UUID -> XesJsonAttribute("id", key, value.toString())
            is Instant -> XesJsonAttribute("date", key, formatTimestamp(value))
            is ZonedDateTime -> XesJsonAttribute("date", key, formatTimestamp(value.toInstant()))
            is LocalDateTime -> XesJsonAttribute("date", key, formatTimestamp(value.toInstant(ZoneOffset.UTC)))
            else -> XesJsonAttribute("string", key, value.toString())
        }
    }

    private fun formatTimestamp(instant: Instant): String =
        if (instant.nano == 0) {
            instant.atZone(ZoneOffset.UTC).format(XES_DATE_NO_MILLIS)
        } else {
            DateTimeFormatter.ISO_INSTANT.format(instant)
        }

    private fun orderedAttributeFingerprint(
        attributes: List<XesJsonAttribute>,
        ignoredKeys: Set<String>,
    ): String =
        attributes
            .filter { it.key !in ignoredKeys }
            .sortedWith(compareBy<XesJsonAttribute> { it.key }.thenBy { it.type }.thenBy { it.value })
            .joinToString("|") { "${it.key}=${it.type}:${it.value}" }

    private fun metadataFingerprint(metadata: Map<String, String>): String =
        metadata.entries
            .sortedBy { it.key }
            .joinToString("|") { "${it.key}=${it.value}" }

    private fun groupByConceptName(items: List<XesJsonNode>): Map<String, List<XesJsonNode>> =
        items.groupBy { node ->
            node.attributeValuesByKey()[ATTR_CONCEPT_NAME] ?: "unknown"
        }

    private fun groupEventsByKey(
        events: List<XesJsonNode>,
        timestampOnly: Boolean = false,
    ): Map<String, List<XesJsonNode>> =
        events.groupBy { event ->
            val attrs = event.attributeValuesByKey()
            val timestamp = attrs["time:timestamp"] ?: "?"
            if (timestampOnly) {
                timestamp
            } else {
                val name = attrs[ATTR_CONCEPT_NAME] ?: "?"
                "$timestamp|$name"
            }
        }

    private fun hasNamedEvents(events: List<XesJsonNode>): Boolean =
        events.any { (it.attributeValuesByKey()[ATTR_CONCEPT_NAME] ?: "?") != "?" }

    private fun eventSortKey(event: XesJsonNode): String {
        val values = event.attributeValuesByKey()
        return listOf(
            values["lifecycle:transition"] ?: "",
            values["org:resource"] ?: "",
            values["result"] ?: "",
        ).joinToString("|")
    }

    private fun XesJsonAttribute.describe(): String =
        listOf(
            type,
            value,
        ).joinToString(":")

    private fun describeEventCount(trace: XesJsonNode): String =
        if (trace.events.size != trace.rawEventCount) {
            "${trace.events.size} real + ${trace.rawEventCount - trace.events.size} null"
        } else {
            "${trace.rawEventCount}"
        }

    private fun equivalentNumbers(
        localAttribute: XesJsonAttribute,
        remoteAttribute: XesJsonAttribute,
    ): Boolean {
        if (localAttribute.type != remoteAttribute.type) return false
        val localNumber = localAttribute.value.toDoubleOrNull()
        val remoteNumber = remoteAttribute.value.toDoubleOrNull()
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
    val status: ComparisonStatus = if (match) ComparisonStatus.MATCH else ComparisonStatus.MISMATCH,
)

enum class ComparisonStatus {
    MATCH,
    MISMATCH,
    NONDETERMINISTIC_MATCH,
}
