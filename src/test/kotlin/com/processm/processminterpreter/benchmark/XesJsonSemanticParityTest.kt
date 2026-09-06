package com.processm.processminterpreter.benchmark

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class XesJsonSemanticParityTest {
    private val raw = """[{"log":{"float":{"@key":"avg(^^event:cost:total)","@value":"3.5"},"int":{"@key":"count(^^event:cost:total)","@value":"6"},"float":{"@key":"sum(^^event:cost:total)","@value":"21.0"}}}]"""
    private val array = """[{"log":{"float":[{"@key":"avg(^^event:cost:total)","@value":"3.5"},{"@key":"sum(^^event:cost:total)","@value":"21.0"}],"int":{"@key":"count(^^event:cost:total)","@value":"6"}}}]"""

    @Test
    fun `repeated sibling fields match their lossless array representation`() {
        assertTrue(XesJsonSemanticParity.compare(array, raw).matches)
        assertFalse(XesJsonSemanticParity.compare(array, raw.replace("3.5", "99.0")).matches)
        val droppedAverage = """[{"log":{"float":{"@key":"sum(^^event:cost:total)","@value":"21.0"},"int":{"@key":"count(^^event:cost:total)","@value":"6"}}}]"""
        assertFalse(XesJsonSemanticParity.compare(droppedAverage, raw).matches)
    }

    @Test
    fun `duplicate attribute and duplicate scalar metadata cannot become matches`() {
        val duplicate = """[{"log":{"int":{"@key":"n","@value":"6"},"int":{"@key":"n","@value":"6"}}}]"""
        val malformed = """[{"log":{"int":{"@key":"n","@value":"7","@value":"6"}}}]"""
        assertFalse(XesJsonSemanticParity.compare(duplicate, duplicate).matches)
        assertFalse(XesJsonSemanticParity.compare(malformed, malformed).matches)
        val expected = ExpectedBenchmarkResponse(1, 0, 0, mapOf("n" to ExpectedBenchmarkResponse.Attribute("int", "6")))
        assertNotNull(expected.mismatch(duplicate))
        assertNotNull(expected.mismatch(malformed))
    }

    @Test
    fun `repeated hierarchy children are all counted`() {
        val body = """[{"log":{"trace":{"event":{}},"trace":[{"event":{},"event":{}}]},"log":{"trace":{}}}]"""
        assertEquals(XesJsonCounts(2, 3, 3), XesJsonCounting.count(body))
    }
}
