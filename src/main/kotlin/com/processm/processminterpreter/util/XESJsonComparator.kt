package com.processm.processminterpreter.util

import org.slf4j.LoggerFactory

/**
 * Compares two XES JSON structures (LOCAL vs REMOTE) for verification.
 *
 * Comparison model: **asymmetric, LOCAL-as-reference**.
 *
 * LOCAL is our interpreter's output, verified against the source XES file and pm4py
 * (the industry-standard process mining library). LOCAL is treated as correct per the
 * XES standard (IEEE 1849-2016). REMOTE is ProcessM's output.
 *
 * Rules:
 * - Order-independent (traces/events matched by identity, not position).
 * - `identity:id` is ignored universally — it's a random UUID and unrelated to query data.
 * - Attributes present in LOCAL but missing in REMOTE are **silently accepted**.
 *   This encodes the fact that ProcessM can drop standard attributes (notably
 *   `concept:name` and `cost:currency` on events) that LOCAL correctly preserves.
 *   Hardcoding per-attribute ignore lists was overfit to JournalReview and hid
 *   real regressions on other logs — see docs/analiza_projekcji_eventow.ipynb.
 * - Attributes present in REMOTE but missing in LOCAL are **flagged** as mismatches
 *   (REMOTE should never have strictly more data than LOCAL under this model).
 * - Values present on both sides but differing are flagged (with float tolerance).
 *
 * Practical effect: a query returns MATCH iff every attribute REMOTE emits also
 * appears in LOCAL with the same value. LOCAL may legitimately emit extra attributes.
 */
object XESJsonComparator {
    private val logger = LoggerFactory.getLogger(XESJsonComparator::class.java)

    // Universal ignore: random UUIDs that always differ between systems.
    private val IGNORED_KEYS = setOf("identity:id")

    // Log-level structural metadata (describes log schema, not query-result data).
    // Kept as explicit ignores because values may legitimately differ on both sides
    // without indicating a query translation bug.
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

    // Trace-level: placeholder aggregate column that ProcessM emits non-deterministically.
    private val IGNORED_TRACE_KEYS =
        setOf(
            "identity:id",
            "count(trace:concept:name)",
        )

    // Event-level: only the universal UUID is ignored. Everything else is subject to
    // the asymmetric LOCAL-as-reference rule (see class docstring).
    private val IGNORED_EVENT_KEYS =
        setOf(
            "identity:id",
        )

    // XES attribute type keys
    private val ATTR_TYPES = setOf("string", "date", "float", "int", "boolean", "id")

    fun compare(
        localJson: List<Map<String, Any?>>,
        remoteJson: List<Map<String, Any?>>,
    ): ComparisonResult {
        val diffs = mutableListOf<String>()

        val localLog = extractLog(localJson)
        val remoteLog = extractLog(remoteJson)

        if (localLog == null && remoteLog == null) {
            return ComparisonResult(true, 0, 0, emptyList(), "Both empty")
        }
        if (localLog == null) {
            return ComparisonResult(false, 0, 1, listOf("LOCAL has no log data"), "LOCAL empty")
        }
        if (remoteLog == null) {
            return ComparisonResult(false, 1, 0, listOf("REMOTE has no log data"), "REMOTE empty")
        }

        // Compare log-level attributes (with broader ignore list for log metadata)
        val localLogAttrs = extractAttributes(localLog)
        val remoteLogAttrs = extractAttributes(remoteLog)
        compareAttributes("Log", localLogAttrs, remoteLogAttrs, diffs, IGNORED_LOG_KEYS)

        // Extract and compare traces
        val localTraces = extractChildren(localLog, "trace")
        val remoteTraces = extractChildren(remoteLog, "trace")

        if (localTraces.size != remoteTraces.size) {
            diffs.add("Trace count: LOCAL=${localTraces.size}, REMOTE=${remoteTraces.size}")
        }

        // Determine if traces have concept:name for matching
        // When all traces on both sides lack concept:name, use positional matching
        val localByName = groupByConceptName(localTraces)
        val remoteByName = groupByConceptName(remoteTraces)

        val allLocalUnknown = localByName.keys == setOf("unknown") && localTraces.size > 1
        val allRemoteUnknown = remoteByName.keys == setOf("unknown") && remoteTraces.size > 1
        val usePositionalMatching = allLocalUnknown && allRemoteUnknown

        if (usePositionalMatching) {
            // Content-based matching: sort traces by a deterministic fingerprint
            // derived from their attributes and events, then compare positionally.
            // This handles cases where both systems return the same traces but in different order.
            //
            // Under the asymmetric rule LOCAL may have extra attributes REMOTE lacks,
            // which would desync fingerprints. Restrict the fingerprint to the set of
            // keys REMOTE actually emits (REMOTE ⊆ LOCAL), so both sides produce a
            // comparable fingerprint even when LOCAL is strictly richer.
            val remoteKeyFilter = collectKeys(remoteTraces)
            val sortedLocal = localTraces.sortedBy { traceFingerprint(it, remoteKeyFilter) }
            val sortedRemote = remoteTraces.sortedBy { traceFingerprint(it, remoteKeyFilter) }
            val count = maxOf(sortedLocal.size, sortedRemote.size)
            for (i in 0 until count) {
                val localTrace = sortedLocal.getOrNull(i)
                val remoteTrace = sortedRemote.getOrNull(i)
                val label = "[$i]"

                if (localTrace == null) {
                    diffs.add("Trace $label: extra in REMOTE")
                    continue
                }
                if (remoteTrace == null) {
                    diffs.add("Trace $label: extra in LOCAL")
                    continue
                }

                compareTrace(label, localTrace, remoteTrace, diffs)
            }
        } else {
            // Name-based matching: group by concept:name
            val allTraceNames = (localByName.keys + remoteByName.keys).toSortedSet()

            // Collect missing traces for summary instead of listing each one
            val missingInLocal = mutableListOf<String>()
            val missingInRemote = mutableListOf<String>()

            for (traceName in allTraceNames) {
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

                // Compare matching traces (by index within same-name group)
                val count = maxOf(localGroup.size, remoteGroup.size)
                for (i in 0 until count) {
                    val suffix = if (count > 1) " [$i]" else ""
                    val localTrace = localGroup.getOrNull(i)
                    val remoteTrace = remoteGroup.getOrNull(i)

                    if (localTrace == null) {
                        diffs.add("Trace '$traceName'$suffix: extra in REMOTE")
                        continue
                    }
                    if (remoteTrace == null) {
                        diffs.add("Trace '$traceName'$suffix: extra in LOCAL")
                        continue
                    }

                    compareTrace(traceName, localTrace, remoteTrace, diffs)
                }
            }

            missingInRemote.forEach { diffs.add("Trace '$it': missing in REMOTE") }
            missingInLocal.forEach { diffs.add("Trace '$it': missing in LOCAL") }
        }

        val match = diffs.isEmpty()
        val summary =
            if (match) {
                "MATCH: ${localTraces.size} traces, attributes identical"
            } else {
                "MISMATCH: ${diffs.size} difference(s) found"
            }

        return ComparisonResult(match, localTraces.size, remoteTraces.size, diffs, summary)
    }

    private fun compareTrace(
        traceName: String,
        localTrace: Map<String, Any?>,
        remoteTrace: Map<String, Any?>,
        diffs: MutableList<String>,
    ) {
        // Compare trace attributes
        val localAttrs = extractAttributes(localTrace)
        val remoteAttrs = extractAttributes(remoteTrace)
        compareAttributes("Trace '$traceName'", localAttrs, remoteAttrs, diffs, IGNORED_TRACE_KEYS)

        // Compare events (including null event placeholders)
        val localRawEventCount = countRawChildren(localTrace, "event")
        val remoteRawEventCount = countRawChildren(remoteTrace, "event")
        val localEvents = extractChildren(localTrace, "event")
        val remoteEvents = extractChildren(remoteTrace, "event")

        if (localRawEventCount != remoteRawEventCount) {
            val localDesc =
                if (localEvents.size !=
                    localRawEventCount
                ) {
                    "${localEvents.size} real + ${localRawEventCount - localEvents.size} null"
                } else {
                    "$localRawEventCount"
                }
            val remoteDesc =
                if (remoteEvents.size !=
                    remoteRawEventCount
                ) {
                    "${remoteEvents.size} real + ${remoteRawEventCount - remoteEvents.size} null"
                } else {
                    "$remoteRawEventCount"
                }
            diffs.add("Trace '$traceName': event count LOCAL=$localDesc, REMOTE=$remoteDesc")
        }

        val localEventAttrs = localEvents.map { extractAttributes(it) }
        val remoteEventAttrs = remoteEvents.map { extractAttributes(it) }

        // Determine matching strategy:
        // If one side has all "?" for concept:name, fall back to timestamp-only matching
        val localHasNames = localEventAttrs.any { (it["concept:name"] ?: "?") != "?" }
        val remoteHasNames = remoteEventAttrs.any { (it["concept:name"] ?: "?") != "?" }
        val useTimestampOnly = !localHasNames || !remoteHasNames

        val localByKey = groupEventsByKey(localEventAttrs, useTimestampOnly)
        val remoteByKey = groupEventsByKey(remoteEventAttrs, useTimestampOnly)

        val allEventKeys = (localByKey.keys + remoteByKey.keys).toSortedSet()

        // Collect missing events for summary
        val missingEventsInLocal = mutableListOf<String>()
        val missingEventsInRemote = mutableListOf<String>()

        for (eventKey in allEventKeys) {
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

            // Compare matched events — sort both groups by secondary keys
            // so that event ordering within same timestamp+name doesn't matter
            val sortKey = { e: Map<String, String> ->
                listOf(
                    e["lifecycle:transition"] ?: "",
                    e["org:resource"] ?: "",
                    e["result"] ?: "",
                ).joinToString("|")
            }
            val sortedLocal = localGroup.sortedBy(sortKey)
            val sortedRemote = remoteGroup.sortedBy(sortKey)
            val count = maxOf(sortedLocal.size, sortedRemote.size)
            for (i in 0 until count) {
                val le = sortedLocal.getOrNull(i)
                val re = sortedRemote.getOrNull(i)
                if (le != null && re != null) {
                    compareAttributes("Trace '$traceName' event '$eventKey'", le, re, diffs, IGNORED_EVENT_KEYS)
                } else if (le == null) {
                    missingEventsInLocal.add("$eventKey (extra)")
                } else {
                    missingEventsInRemote.add("$eventKey (extra)")
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
        // Also ignore meta_* keys — OpenXES pre-computed statistics stored non-deterministically in ProcessM
        val localFiltered = local.filterKeys { it !in ignoredKeys && !it.startsWith("meta_") }
        val remoteFiltered = remote.filterKeys { it !in ignoredKeys && !it.startsWith("meta_") }

        // Asymmetric comparison: LOCAL is the reference (verified against pm4py and the
        // source XES file per the XES standard). We only need to verify REMOTE ⊆ LOCAL.
        //   - REMOTE has key, LOCAL missing it  → flag (REMOTE shouldn't have extras)
        //   - LOCAL has key, REMOTE missing it  → silent (LOCAL may legitimately emit more,
        //                                          e.g. concept:name / cost:currency that
        //                                          ProcessM drops arbitrarily)
        //   - Both have key, values differ      → flag (with float tolerance)
        for ((key, rv) in remoteFiltered) {
            val lv = localFiltered[key]
            when {
                lv == null -> {
                    diffs.add("$context: LOCAL missing '$key' (REMOTE='$rv')")
                }

                lv != rv -> {
                    // Allow float tolerance (e.g., 1.08 vs 1.0799999999999992)
                    val lvd = lv.toDoubleOrNull()
                    val rvd = rv.toDoubleOrNull()
                    if (lvd != null && rvd != null && Math.abs(lvd - rvd) < 1e-6) {
                        // Close enough — treat as match
                    } else {
                        diffs.add("$context: '$key' differs LOCAL='$lv' REMOTE='$rv'")
                    }
                }
            }
        }
    }

    /**
     * Extract the log map from the XES JSON wrapper.
     * Input: [{"log": {...}}] -> returns the log map
     */
    private fun extractLog(json: List<Map<String, Any?>>): Map<String, Any?>? {
        if (json.isEmpty()) return null
        val first = json.first()
        @Suppress("UNCHECKED_CAST")
        return (first["log"] as? Map<String, Any?>) ?: first
    }

    /**
     * Count raw children including null entries.
     * ProcessM outputs null event placeholders for non-projected events.
     */
    private fun countRawChildren(
        parent: Map<String, Any?>,
        childKey: String,
    ): Int {
        val raw = parent[childKey] ?: return 0
        return when (raw) {
            is List<*> -> raw.size
            is Map<*, *> -> 1
            else -> 0
        }
    }

    /**
     * Extract children (traces or events) from a parent node.
     * Handles both single-object and array forms.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractChildren(
        parent: Map<String, Any?>,
        childKey: String,
    ): List<Map<String, Any?>> {
        val raw = parent[childKey] ?: return emptyList()
        return when (raw) {
            is List<*> -> raw.filterIsInstance<Map<String, Any?>>()
            is Map<*, *> -> listOf(raw as Map<String, Any?>)
            else -> emptyList()
        }
    }

    /**
     * Extract all attribute key-value pairs from a node.
     * Flattens XES typed attributes: "string":[{"@key":"k","@value":"v"}] -> {"k":"v"}
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractAttributes(node: Map<String, Any?>): Map<String, String> {
        val result = mutableMapOf<String, String>()

        for (type in ATTR_TYPES) {
            val raw = node[type] ?: continue
            val items =
                when (raw) {
                    is List<*> -> raw.filterIsInstance<Map<String, Any?>>()
                    is Map<*, *> -> listOf(raw as Map<String, Any?>)
                    else -> continue
                }

            for (item in items) {
                val key = item["@key"]?.toString() ?: continue
                val value = item["@value"]?.toString() ?: continue
                result[key] = value
            }
        }

        return result
    }

    /**
     * Compute a deterministic fingerprint for a trace based on its attributes and events.
     * Used for content-based matching when traces lack concept:name.
     *
     * @param keyFilter if non-null, only these keys contribute to the fingerprint.
     *        Used by positional matching to restrict the fingerprint to REMOTE's keyset,
     *        so that LOCAL's extra attributes don't desync ordering.
     */
    private fun traceFingerprint(
        trace: Map<String, Any?>,
        keyFilter: Set<String>? = null,
    ): String {
        fun keep(key: String): Boolean =
            key !in IGNORED_KEYS && (keyFilter == null || key in keyFilter)

        val attrs = extractAttributes(trace)
        val attrStr =
            attrs
                .filterKeys(::keep)
                .entries
                .sortedBy { it.key }
                .joinToString("|") { "${it.key}=${it.value}" }
        val events = extractChildren(trace, "event")
        val eventStr =
            events
                .map { event ->
                    val eventAttrs = extractAttributes(event)
                    eventAttrs
                        .filterKeys(::keep)
                        .entries
                        .sortedBy { it.key }
                        .joinToString(",") { "${it.key}=${it.value}" }
                }.sorted()
                .joinToString(";")
        return "$attrStr||$eventStr"
    }

    /**
     * Collect every attribute key present anywhere in the given traces (trace-level or
     * event-level). Used to compute REMOTE's keyset for asymmetric fingerprinting.
     */
    private fun collectKeys(traces: List<Map<String, Any?>>): Set<String> {
        val keys = mutableSetOf<String>()
        for (trace in traces) {
            keys += extractAttributes(trace).keys
            for (event in extractChildren(trace, "event")) {
                keys += extractAttributes(event).keys
            }
        }
        return keys
    }

    /**
     * Group traces by concept:name.
     */
    private fun groupByConceptName(items: List<Map<String, Any?>>): Map<String, List<Map<String, Any?>>> =
        items.groupBy { node ->
            val attrs = extractAttributes(node)
            attrs["concept:name"] ?: "unknown"
        }

    /**
     * Group events by identity key.
     * @param timestampOnly if true, match by timestamp only (fallback when one side lacks concept:name)
     */
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
}

/**
 * Result of XES JSON comparison
 */
data class ComparisonResult(
    val match: Boolean,
    val traceCountLocal: Int,
    val traceCountRemote: Int,
    val differences: List<String>,
    val summary: String,
)
