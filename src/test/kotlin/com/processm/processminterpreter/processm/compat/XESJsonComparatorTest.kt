package com.processm.processminterpreter.processm.compat

import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XESJsonComparatorTest {
    @Test
    fun `local-only attributes are mismatches`() {
        val local = xesLog(traceAttrs = attrs("concept:name" to "0", "description" to "extra"))
        val remote = xesLog(traceAttrs = attrs("concept:name" to "0"))

        val result = XESJsonComparator.compare(local, remote)

        assertFalse(result.match)
        assertTrue(
            result.differences.any { it.contains("REMOTE missing 'description'") },
            result.differences.joinToString("\n"),
        )
    }

    @Test
    fun `duplicate XES attributes are mismatches`() {
        val local = xesLog(
            traceAttrs = listOf(
                attr("concept:name", "0"),
                attr("concept:name", "0"),
            ),
        )
        val remote = xesLog(traceAttrs = attrs("concept:name" to "0"))

        val result = XESJsonComparator.compare(local, remote)

        assertFalse(result.match)
        assertTrue(
            result.differences.any { it.contains("duplicate XES attribute 'concept:name'") },
            result.differences.joinToString("\n"),
        )
    }

    @Test
    fun `null event placeholders count towards event mismatch detection`() {
        val local =
            listOf(
                mapOf(
                    "log" to
                        mapOf(
                            "trace" to
                                mapOf(
                                    "string" to attr("concept:name", "0"),
                                    "event" to listOf(null, null),
                                ),
                        ),
                ),
            )
        val remote =
            listOf(
                mapOf(
                    "log" to
                        mapOf(
                            "trace" to
                                mapOf(
                                    "string" to attr("concept:name", "0"),
                                    "event" to listOf(null),
                                ),
                        ),
                ),
            )

        val result = XESJsonComparator.compare(local, remote)

        assertFalse(result.match)
        assertTrue(
            result.differences.any { it.contains("event count LOCAL=0 real + 2 null, REMOTE=0 real + 1 null") },
            result.differences.joinToString("\n"),
        )
    }

    @Test
    fun `nested same-name children are treated as siblings`() {
        val local =
            listOf(
                mapOf(
                    "log" to
                        mapOf(
                            "trace" to
                                listOf(
                                    mapOf("string" to attr("concept:name", "0")),
                                    mapOf("string" to attr("concept:name", "1")),
                                ),
                        ),
                ),
            )
        val remote =
            listOf(
                mapOf(
                    "log" to
                        mapOf(
                            "trace" to
                                mapOf(
                                    "string" to attr("concept:name", "0"),
                                    "trace" to mapOf("string" to attr("concept:name", "1")),
                                ),
                        ),
                ),
            )

        val result = XESJsonComparator.compare(local, remote)

        assertTrue(result.match, result.differences.joinToString("\n"))
    }

    @Test
    fun `identity id values and attribute order do not affect match`() {
        val local =
            listOf(
                mapOf(
                    "log" to
                        mapOf(
                            "string" to attrs("source" to "CPN Tools simulation", "lifecycle:model" to "standard"),
                            "id" to attr("identity:id", "00000000-0000-0000-0000-000000000001"),
                            "trace" to
                                mapOf(
                                    "string" to attrs("concept:name" to "0", "description" to "Simulated process instance"),
                                    "event" to
                                        mapOf(
                                            "string" to attrs(
                                                "concept:name" to "incoming claim",
                                                "lifecycle:transition" to "complete",
                                                "org:resource" to "customer",
                                                "call centre" to "Brisbane",
                                            ),
                                            "date" to attr("time:timestamp", "1970-01-01T00:00:00Z"),
                                        ),
                                ),
                        ),
                ),
            )
        val remote =
            listOf(
                mapOf(
                    "log" to
                        mapOf(
                            "string" to attrs("lifecycle:model" to "standard", "source" to "CPN Tools simulation"),
                            "id" to attr("identity:id", "53b51880-301c-4c98-ae28-30bdc8e60d48"),
                            "trace" to
                                mapOf(
                                    "string" to attrs("description" to "Simulated process instance", "concept:name" to "0"),
                                    "event" to
                                        mapOf(
                                            "string" to attrs(
                                                "call centre" to "Brisbane",
                                                "concept:name" to "incoming claim",
                                                "lifecycle:transition" to "complete",
                                                "org:resource" to "customer",
                                            ),
                                            "date" to attr("time:timestamp", "1970-01-01T00:00:00Z"),
                                        ),
                                ),
                        ),
                ),
            )

        val result = XESJsonComparator.compare(local, remote)

        assertTrue(result.match, result.differences.joinToString("\n"))
    }

    @Test
    fun `trace aggregate attributes are part of the comparison contract`() {
        val local = xesLog(traceAttrs = attrs("concept:name" to "0", "count(trace:concept:name)" to "2"))
        val remote = xesLog(traceAttrs = attrs("concept:name" to "0", "count(trace:concept:name)" to "1"))

        val result = XESJsonComparator.compare(local, remote)

        assertFalse(result.match)
        assertTrue(
            result.differences.any { it.contains("'count(trace:concept:name)' differs") },
            result.differences.joinToString("\n"),
        )
    }

    @Test
    fun `anonymous null-event traces are paired by placeholder count`() {
        val local =
            xesLogWithTraces(
                traceWithNullEvents(count = 11, ignoredCount = "2"),
                traceWithNullEvents(count = 20, ignoredCount = "2"),
            )
        val remote =
            xesLogWithTraces(
                traceWithNullEvents(count = 20, ignoredCount = "2"),
                traceWithNullEvents(count = 11, ignoredCount = "2"),
            )

        val result = XESJsonComparator.compare(local, remote)

        assertTrue(result.match, result.differences.joinToString("\n"))
    }

    @Test
    fun `missing identity id is a mismatch even though uuid values are ignored`() {
        val local =
            listOf(
                mapOf(
                    "log" to
                        mapOf(
                            "string" to attrs("source" to "CPN Tools simulation"),
                        ),
                ),
            )
        val remote =
            listOf(
                mapOf(
                    "log" to
                        mapOf(
                            "string" to attrs("source" to "CPN Tools simulation"),
                            "id" to attr("identity:id", "53b51880-301c-4c98-ae28-30bdc8e60d48"),
                        ),
                ),
            )

        val result = XESJsonComparator.compare(local, remote)

        assertFalse(result.match)
        assertTrue(
            result.differences.any { it.contains("LOCAL missing 'identity:id'") },
            result.differences.joinToString("\n"),
        )
    }

    @Test
    fun `anonymous trace mismatches are reported as multiset differences`() {
        val local =
            xesLogWithTraces(
                trace(listOf("A", "B"), ignoredCount = "1"),
                trace(listOf("C"), ignoredCount = "1"),
            )
        val remote =
            xesLogWithTraces(
                trace(listOf("A", "B"), ignoredCount = "1"),
                trace(listOf("D"), ignoredCount = "1"),
            )

        val result = XESJsonComparator.compare(local, remote)

        assertFalse(result.match)
        assertEquals(2, result.differences.size, result.differences.joinToString("\n"))
        assertTrue(
            result.differences.any { it.contains("extra in LOCAL") && it.contains("events=[C]") },
            result.differences.joinToString("\n"),
        )
        assertTrue(
            result.differences.any { it.contains("extra in REMOTE") && it.contains("events=[D]") },
            result.differences.joinToString("\n"),
        )
    }

    @Test
    fun `remote trace subset can be classified as nondeterministic ProcessM window`() {
        val local =
            xesLogWithTraces(
                trace(listOf("A", "B"), ignoredCount = "1"),
                trace(listOf("C", "D"), ignoredCount = "1"),
            )
        val remote =
            xesLogWithTraces(
                trace(listOf("C", "D"), ignoredCount = "1"),
            )

        val result = XESJsonComparator.compareRemoteTraceSubset(local, remote)

        assertFalse(result.match)
        assertEquals(ComparisonStatus.NONDETERMINISTIC_MATCH, result.status)
        assertTrue(result.differences.isEmpty(), result.differences.joinToString("\n"))
    }

    @Test
    fun `remote trace subset can be checked against domain logs without JSON formatting`() {
        val localLogs =
            listOf(
                XesLog(
                    traces = listOf(
                        domainTraceWithCount("A", "B"),
                        domainTraceWithCount("C", "D"),
                    ),
                ),
            )
        val remote =
            xesLogWithTraces(
                trace(listOf("C", "D"), ignoredCount = "1"),
            )

        val result = XESJsonComparator.compareRemoteTraceSubset(
            localLogs = localLogs,
            remoteJson = remote,
            isProjectedQuery = false,
            projectedTraceStandardAttributes = emptySet(),
            includeEvents = true,
        )

        assertFalse(result.match)
        assertEquals(ComparisonStatus.NONDETERMINISTIC_MATCH, result.status)
        assertTrue(result.differences.isEmpty(), result.differences.joinToString("\n"))
    }

    @Test
    fun `grouped event order differences can be classified as nondeterministic`() {
        val local = xesLogWithTraces(
            trace(listOf("A", "B"), ignoredCount = "1"),
            trace(listOf("C"), ignoredCount = "1"),
        )
        val remote = xesLogWithTraces(
            trace(listOf("B", "A"), ignoredCount = "1"),
            trace(listOf("C"), ignoredCount = "1"),
        )

        val strict = XESJsonComparator.compare(local, remote)
        val orderInsensitive = XESJsonComparator.compareIgnoringEventOrder(local, remote)

        assertFalse(strict.match)
        assertEquals(ComparisonStatus.NONDETERMINISTIC_MATCH, orderInsensitive.status)
        assertTrue(orderInsensitive.differences.isEmpty(), orderInsensitive.differences.joinToString("\n"))
    }

    @Test
    fun `grouped event order compatibility still rejects missing event attributes`() {
        val local =
            xesLogWithTraces(
                mapOf(
                    "event" to listOf(
                        mapOf("string" to attrs("concept:name" to "A", "sum(event:cost:total)" to "1.0")),
                    ),
                ),
            )
        val remote =
            xesLogWithTraces(
                mapOf(
                    "event" to listOf(
                        mapOf("string" to attr("concept:name", "A")),
                    ),
                ),
            )

        val result = XESJsonComparator.compareIgnoringEventOrder(local, remote)

        assertFalse(result.match)
        assertEquals(ComparisonStatus.MISMATCH, result.status)
        assertTrue(result.differences.isNotEmpty(), result.differences.joinToString("\n"))
    }

    @Test
    fun `remote trace subset can ignore grouped event order against domain logs`() {
        val localLogs =
            listOf(
                XesLog(
                    traces = listOf(domainTraceWithCount("A", "B")),
                ),
            )
        val remote = xesLogWithTraces(trace(listOf("B", "A"), ignoredCount = "1"))

        val result = XESJsonComparator.compareRemoteTraceSubset(
            localLogs = localLogs,
            remoteJson = remote,
            isProjectedQuery = false,
            projectedTraceStandardAttributes = emptySet(),
            includeEvents = true,
            ignoreEventOrder = true,
        )

        assertEquals(ComparisonStatus.NONDETERMINISTIC_MATCH, result.status)
        assertTrue(result.differences.isEmpty(), result.differences.joinToString("\n"))
    }

    @Test
    fun `remote trace subset can allow grouped event window against domain logs`() {
        val localLogs =
            listOf(
                XesLog(
                    traces = listOf(domainTraceWithCount("A", "B", "C")),
                ),
            )
        val remote = xesLogWithTraces(trace(listOf("C", "A"), ignoredCount = "1"))

        val result = XESJsonComparator.compareRemoteTraceSubset(
            localLogs = localLogs,
            remoteJson = remote,
            isProjectedQuery = false,
            projectedTraceStandardAttributes = emptySet(),
            includeEvents = true,
            ignoreEventOrder = true,
            allowEventSubset = true,
        )

        assertEquals(ComparisonStatus.NONDETERMINISTIC_MATCH, result.status)
        assertTrue(result.differences.isEmpty(), result.differences.joinToString("\n"))
    }

    @Test
    fun `trace window classifier only accepts trace count and extra trace differences`() {
        val traceWindow = ComparisonResult(
            match = false,
            traceCountLocal = 2,
            traceCountRemote = 1,
            differences = listOf(
                "Trace count: LOCAL=2, REMOTE=1",
                "anonymous traces: 1 equivalent trace(s) extra in LOCAL: attrs=[no trace attributes]",
            ),
            summary = "MISMATCH",
        )
        val attributeMismatch = ComparisonResult(
            match = false,
            traceCountLocal = 1,
            traceCountRemote = 1,
            differences = listOf("Log: LOCAL missing metadata 'extension' (REMOTE='x')"),
            summary = "MISMATCH",
        )

        assertTrue(XESJsonComparator.hasOnlyTraceWindowDifferences(traceWindow))
        assertFalse(XESJsonComparator.hasOnlyTraceWindowDifferences(attributeMismatch))
    }

    @Test
    fun `now attributes are compared as volatile timestamps`() {
        val local = xesLogWithLogAttrs(attrs("log:now()" to "2026-05-21T21:29:32Z"))
        val remote = xesLogWithLogAttrs(attrs("log:now()" to "2026-05-21T21:29:35Z"))

        val result = XESJsonComparator.compare(local, remote)

        assertTrue(result.match, result.differences.joinToString("\n"))
    }

    @Test
    fun `unscoped now attributes are compared as volatile timestamps`() {
        val local = xesLogWithLogAttrs(attrs("now()" to "2026-05-21T21:29:32Z"))
        val remote = xesLogWithLogAttrs(attrs("now()" to "2026-05-21T21:29:35Z"))

        val result = XESJsonComparator.compare(local, remote)

        assertTrue(result.match, result.differences.joinToString("\n"))
    }

    @Test
    fun `stale now attributes are still mismatches`() {
        val local = xesLogWithLogAttrs(attrs("log:now()" to "2026-05-21T21:29:32Z"))
        val remote = xesLogWithLogAttrs(attrs("log:now()" to "2026-05-21T21:40:00Z"))

        val result = XESJsonComparator.compare(local, remote)

        assertFalse(result.match)
        assertTrue(
            result.differences.any { it.contains("'log:now()' differs") },
            result.differences.joinToString("\n"),
        )
    }

    @Test
    fun `meta attributes are part of the comparison contract`() {
        val local = xesLogWithStringLogAttrs(attrs("meta_org:group_events_total" to "150291"))
        val remote = xesLogWithStringLogAttrs(emptyList())

        val result = XESJsonComparator.compare(local, remote)

        assertFalse(result.match)
        assertTrue(
            result.differences.any { it.contains("REMOTE missing 'meta_org:group_events_total'") },
            result.differences.joinToString("\n"),
        )
    }

    @Test
    fun `missing log extensions are mismatches`() {
        val local = listOf(mapOf("log" to emptyMap<String, Any?>()))
        val remote =
            listOf(
                mapOf(
                    "log" to mapOf(
                        "extension" to mapOf(
                            "@name" to "Organizational",
                            "@prefix" to "org",
                            "@uri" to "http://www.xes-standard.org/org.xesext",
                        ),
                    ),
                ),
            )

        val result = XESJsonComparator.compare(local, remote)

        assertFalse(result.match)
        assertTrue(
            result.differences.any { it.contains("LOCAL missing metadata 'extension'") },
            result.differences.joinToString("\n"),
        )
    }

    @Test
    fun `missing structural scalar metadata is a mismatch`() {
        val local = listOf(mapOf("log" to emptyMap<String, Any?>()))
        val remote =
            listOf(
                mapOf(
                    "log" to mapOf(
                        "@xes.version" to "1.0",
                        "@xmlns" to "http://www.xes-standard.org/",
                    ),
                ),
            )

        val result = XESJsonComparator.compare(local, remote)

        assertFalse(result.match)
        assertTrue(
            result.differences.any { it.contains("LOCAL missing metadata '@xes.version'") },
            result.differences.joinToString("\n"),
        )
        assertTrue(
            result.differences.any { it.contains("LOCAL missing metadata '@xmlns'") },
            result.differences.joinToString("\n"),
        )
    }

    @Test
    fun `missing log classifiers are mismatches`() {
        val local = listOf(mapOf("log" to emptyMap<String, Any?>()))
        val remote =
            listOf(
                mapOf(
                    "log" to mapOf(
                        "classifier" to mapOf(
                            "@name" to "Resource",
                            "@scope" to "event",
                            "@keys" to "org:resource",
                        ),
                    ),
                ),
            )

        val result = XESJsonComparator.compare(local, remote)

        assertFalse(result.match)
        assertTrue(
            result.differences.any { it.contains("LOCAL missing metadata 'classifier'") },
            result.differences.joinToString("\n"),
        )
    }

    @Test
    fun `singleton structural metadata object and array are equivalent`() {
        val classifier =
            mapOf(
                "@name" to "Activity",
                "@scope" to "event",
                "@keys" to "concept:name",
            )
        val local = listOf(mapOf("log" to mapOf("classifier" to listOf(classifier))))
        val remote = listOf(mapOf("log" to mapOf("classifier" to classifier)))

        val result = XESJsonComparator.compare(local, remote)

        assertTrue(result.match, result.differences.joinToString("\n"))
    }

    @Test
    fun `global attribute metadata is compared deeply`() {
        val local =
            listOf(
                mapOf(
                    "log" to mapOf(
                        "global" to mapOf(
                            "@scope" to "event",
                            "string" to attr("concept:name", "UNKNOWN"),
                        ),
                    ),
                ),
            )
        val remote =
            listOf(
                mapOf(
                    "log" to mapOf(
                        "global" to mapOf(
                            "@scope" to "event",
                            "float" to attr("cost:total", "0.0"),
                        ),
                    ),
                ),
            )

        val result = XESJsonComparator.compare(local, remote)

        assertFalse(result.match)
        assertTrue(
            result.differences.any { it.contains("metadata 'global' differs") },
            result.differences.joinToString("\n"),
        )
    }

    @Test
    fun `xes attribute type differences are mismatches`() {
        val local = xesLogWithStringLogAttrs(attrs("meta_org:role_events_total" to "150291"))
        val remote = xesLogWithIntLogAttrs(attrs("meta_org:role_events_total" to "150291"))

        val result = XESJsonComparator.compare(local, remote)

        assertFalse(result.match)
        assertTrue(
            result.differences.any { it.contains("'meta_org:role_events_total' type differs") },
            result.differences.joinToString("\n"),
        )
    }

    private fun xesLog(traceAttrs: List<Map<String, Any?>>): List<Map<String, Any?>> =
        listOf(
            mapOf(
                "log" to mapOf(
                    "trace" to mapOf(
                        "string" to traceAttrs,
                    ),
                ),
            ),
        )

    private fun xesLogWithTraces(vararg traces: Map<String, Any?>): List<Map<String, Any?>> =
        listOf(mapOf("log" to mapOf("trace" to traces.toList())))

    private fun xesLogWithLogAttrs(logAttrs: List<Map<String, Any?>>): List<Map<String, Any?>> =
        listOf(mapOf("log" to mapOf("date" to logAttrs)))

    private fun xesLogWithStringLogAttrs(logAttrs: List<Map<String, Any?>>): List<Map<String, Any?>> =
        listOf(mapOf("log" to mapOf("string" to logAttrs)))

    private fun xesLogWithIntLogAttrs(logAttrs: List<Map<String, Any?>>): List<Map<String, Any?>> =
        listOf(mapOf("log" to mapOf("int" to logAttrs)))

    private fun trace(
        eventNames: List<String>,
        ignoredCount: String,
    ): Map<String, Any?> =
        mapOf(
            "int" to attr("count(trace:concept:name)", ignoredCount),
            "event" to eventNames.map { eventName ->
                mapOf("string" to attr("concept:name", eventName))
            },
        )

    private fun traceWithNullEvents(
        count: Int,
        ignoredCount: String,
    ): Map<String, Any?> =
        mapOf(
            "int" to attr("count(trace:concept:name)", ignoredCount),
            "event" to List(count) { null },
        )

    private fun domainTrace(vararg eventNames: String): XesTrace =
        XesTrace(events = eventNames.map { XesEvent(conceptName = it) })

    private fun domainTraceWithCount(vararg eventNames: String): XesTrace =
        XesTrace(
            customAttributes = mapOf("count(trace:concept:name)" to 1),
            events = eventNames.map { XesEvent(conceptName = it) },
        )

    private fun attrs(vararg entries: Pair<String, String>): List<Map<String, Any?>> =
        entries.map { (key, value) -> attr(key, value) }

    private fun attr(
        key: String,
        value: String,
    ): Map<String, Any?> = mapOf("@key" to key, "@value" to value)
}
