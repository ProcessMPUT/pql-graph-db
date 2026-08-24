package com.processm.processminterpreter.xes.io

import com.processm.processminterpreter.xes.model.XesAttributeValue
import kotlin.test.Test
import kotlin.test.assertEquals

class XesParserEmptyAttributeKeyTest {
    @Test
    fun `parser preserves an explicitly empty nested attribute key`() {
        val xml =
            """
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <float key="metric" value="1.376">
                    <float key="" value="1.376"/>
                </float>
            </log>
            """.trimIndent()

        val log = XESParser().parseXesLog(xml.byteInputStream())
        val metric = log.customAttributes.getValue("metric") as XesAttributeValue

        assertEquals(1.376, metric.value)
        assertEquals(1.376, metric.children.getValue(""))
    }

    @Test
    fun `attribute without key is consumed before following traces are read`() {
        val xml =
            """
            <log xes.version="1.0" xmlns="http://www.xes-standard.org/">
                <string value="ignored">
                    <string key="nested" value="also ignored"/>
                </string>
                <trace>
                    <string key="concept:name" value="case-1"/>
                </trace>
            </log>
            """.trimIndent()

        val log = XESParser().parseXesLog(xml.byteInputStream())

        assertEquals(listOf("case-1"), log.traces.map { it.conceptName })
    }
}
