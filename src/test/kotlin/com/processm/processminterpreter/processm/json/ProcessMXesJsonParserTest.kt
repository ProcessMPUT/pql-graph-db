package com.processm.processminterpreter.processm.json

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProcessMXesJsonParserTest {
    @Test
    fun `keeps aggregate attributes from separated scalar and array type runs`() {
        // ProcessM StAXON's raw response for AVG, name, COUNT, MAX, MIN and SUM
        // contains two float fields. The first must not disappear during parsing.
        val raw = """[{"log":{"float":{"@key":"avg(^^event:cost:total)","@value":"3.5"},"string":{"@key":"concept:name","@value":"L"},"int":{"@key":"count(^^event:cost:total)","@value":"6"},"float":[{"@key":"max(^^event:cost:total)","@value":"6.0"},{"@key":"min(^^event:cost:total)","@value":"1.0"},{"@key":"sum(^^event:cost:total)","@value":"21.0"}]}}]"""
        val floats = ProcessMXesJsonParser.parse(raw)[0]["log"]["float"]
        assertEquals(listOf("3.5", "6.0", "1.0", "21.0"), floats.map { it["@value"].asText() })
        assertEquals("avg(^^event:cost:total)", floats[0]["@key"].asText())
    }

    @Test
    fun `preserves nested repeated children nulls and duplicate XES attributes`() {
        val raw = """{"log":{"trace":{"event":null,"event":[{"string":{"@key":"x","@value":"1"},"string":{"@key":"x","@value":"2"}},null],"event":{}},"trace":{"event":{}}}}"""
        val traces = ProcessMXesJsonParser.parse(raw)["log"]["trace"]
        assertEquals(2, traces.size())
        val events = traces[0]["event"]
        assertEquals(4, events.size())
        assertTrue(events[0].isNull)
        assertTrue(events[2].isNull)
        assertEquals(listOf("1", "2"), events[1]["string"].map { it["@value"].asText() })
    }

    @Test
    fun `rejects duplicate XML attributes unknown fields and trailing documents`() {
        for (body in listOf(
            """{"string":{"@key":"x","@key":"y","@value":"1"}}""",
            """{"string":{"@key":"x","@value":"1","@value":"2"}}""",
            """{"data":[],"data":[]}""",
            "[] []",
        )) {
            assertFailsWith<IllegalArgumentException>(body) { ProcessMXesJsonParser.parse(body) }
        }
    }
}
