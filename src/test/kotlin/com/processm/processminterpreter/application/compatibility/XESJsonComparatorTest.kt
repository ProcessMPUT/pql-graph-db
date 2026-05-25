package com.processm.processminterpreter.application.compatibility

import kotlin.test.Test
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
    fun `remote identity id and attribute order do not affect match`() {
        val local =
            listOf(
                mapOf(
                    "log" to
                        mapOf(
                            "string" to attrs("source" to "CPN Tools simulation", "lifecycle:model" to "standard"),
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
    fun `ignored trace attributes do not disturb anonymous trace pairing`() {
        val local =
            xesLogWithTraces(
                trace(eventNames = listOf("A"), ignoredCount = "2"),
                trace(eventNames = listOf("B"), ignoredCount = "1"),
            )
        val remote =
            xesLogWithTraces(
                trace(eventNames = listOf("A"), ignoredCount = "1"),
                trace(eventNames = listOf("B"), ignoredCount = "2"),
            )

        val result = XESJsonComparator.compare(local, remote)

        assertTrue(result.match, result.differences.joinToString("\n"))
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
    fun `now attributes are compared as volatile timestamps`() {
        val local = xesLogWithLogAttrs(attrs("log:now()" to "2026-05-21T21:29:32Z"))
        val remote = xesLogWithLogAttrs(attrs("log:now()" to "2026-05-21T21:29:35Z"))

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

    private fun attrs(vararg entries: Pair<String, String>): List<Map<String, Any?>> =
        entries.map { (key, value) -> attr(key, value) }

    private fun attr(
        key: String,
        value: String,
    ): Map<String, Any?> = mapOf("@key" to key, "@value" to value)
}
