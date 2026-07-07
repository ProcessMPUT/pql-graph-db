package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class XesJsonCountingTest {
    @Test
    fun `counts single log with single trace and single event objects`() {
        val body = """{"log": {"concept:name": "L", "trace": {"concept:name": "T", "event": {"concept:name": "E"}}}}"""
        assertEquals(XesJsonCounts(logs = 1, traces = 1, events = 1), XesJsonCounting.count(body))
    }

    @Test
    fun `counts array of logs with array traces and array events`() {
        val body = """
            [
              {"log": {"trace": [
                {"event": [{"a": 1}, {"a": 2}]},
                {"event": [{"a": 3}]}
              ]}},
              {"log": {"trace": {"event": {"a": 4}}}}
            ]
        """.trimIndent()
        assertEquals(XesJsonCounts(logs = 2, traces = 3, events = 4), XesJsonCounting.count(body))
    }

    @Test
    fun `mixes single object and array shapes at each level`() {
        val body = """
            [
              {"log": {"trace": {"event": [{"x": 1}, {"x": 2}]}}},
              {"log": {"trace": [{"event": {"x": 3}}, {"x": "no events"}]}}
            ]
        """.trimIndent()
        assertEquals(XesJsonCounts(logs = 2, traces = 3, events = 3), XesJsonCounting.count(body))
    }

    @Test
    fun `follows nested single-or-array sibling convention`() {
        val body = """
            {"log": {"trace": {"name": "t1", "trace": {"name": "t2", "event": {"e": 1}}, "event": {"e": 2}}}}
        """.trimIndent()
        assertEquals(XesJsonCounts(logs = 1, traces = 2, events = 2), XesJsonCounting.count(body))
    }

    @Test
    fun `handles logs without traces and traces without events`() {
        val body = """[{"log": {"concept:name": "empty"}}, {"log": {"trace": {"name": "t"}}}]"""
        assertEquals(XesJsonCounts(logs = 2, traces = 1, events = 0), XesJsonCounting.count(body))
    }

    @Test
    fun `null trace and null event count as zero`() {
        val body = """{"log": {"trace": null}}"""
        assertEquals(XesJsonCounts(logs = 1, traces = 0, events = 0), XesJsonCounting.count(body))
    }

    @Test
    fun `blank invalid or non-log bodies count as empty`() {
        assertEquals(XesJsonCounts.EMPTY, XesJsonCounting.count(""))
        assertEquals(XesJsonCounts.EMPTY, XesJsonCounting.count("   "))
        assertEquals(XesJsonCounts.EMPTY, XesJsonCounting.count("not json"))
        assertEquals(XesJsonCounts.EMPTY, XesJsonCounting.count("""{"rows": []}"""))
        assertEquals(XesJsonCounts.EMPTY, XesJsonCounting.count("""{"log": "not an object"}"""))
    }
}
