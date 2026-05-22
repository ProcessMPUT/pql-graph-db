package com.processm.processminterpreter.processm.log

import com.processm.processminterpreter.infrastructure.xes.XESParser
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * XES Import Certification tests adapted from ProcessM XESImportCertificationFirstLevelTest.kt
 *
 * Tests based on: XESImportCertificationFirstLevelTest.kt
 * Original: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/log/XESImportCertificationFirstLevelTest.kt
 */
class XESImportCertificationTest {
    private val parser = XESParser()

    @Test
    fun `A1 - parser handles concept name and identity id`() {
        val xes =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xes.features="nested-attributes" openxes.version="1.0RC7" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <extension name="Identity" prefix="identity" uri="http://www.xes-standard.org/identity.xesext"/>
                <string key="concept:name" value="Log concept:name"/>
                <id key="identity:id" value="bbf3f64f-2507-4f0b-a6f8-0113377d69e4"/>
                <trace>
                    <string key="concept:name" value="Trace #001"/>
                    <id key="identity:id" value="ae1a2f41-2d01-479d-b6a3-84f18d790b20"/>
                    <event>
                        <string key="concept:name" value="Event #1 in Trace #001"/>
                        <id key="identity:id" value="1419fcd5-8fed-4272-8037-453213d8b0d1"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Event #2 in Trace #001"/>
                        <id key="identity:id" value="0e461b08-4f5e-4aa2-b0a2-b7779c82b119"/>
                    </event>
                </trace>
                <trace>
                    <string key="concept:name" value="Trace #002"/>
                    <id key="identity:id" value="a192d6c5-683b-4188-8f73-222227dd4796"/>
                    <event>
                        <string key="concept:name" value="Event #1 in Trace #002"/>
                        <id key="identity:id" value="1a912b4d-6c78-4a4e-8e0f-219fcea53ea7"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Event #2 in Trace #002"/>
                        <id key="identity:id" value="8f14c2ec-83eb-4843-9cb4-c45456a5f3cb"/>
                    </event>
                </trace>
            </log>
            """.trimIndent()

        val result = parse(xes)

        assertEquals(2, result.extensions.size)
        assertEquals("Concept", result.extensions.first { it.prefix == "concept" }.name)
        assertEquals("Identity", result.extensions.first { it.prefix == "identity" }.name)

        assertEquals("Log concept:name", result.conceptName)
        assertEquals("bbf3f64f-2507-4f0b-a6f8-0113377d69e4", result.identityId?.toString())

        assertEquals(2, result.traces.size)

        val trace1 = result.traces[0]
        assertEquals("Trace #001", trace1.conceptName)
        assertEquals("ae1a2f41-2d01-479d-b6a3-84f18d790b20", trace1.identityId?.toString())
        assertEquals(2, trace1.events.size)
        assertEquals("Event #1 in Trace #001", trace1.events[0].conceptName)
        assertEquals("1419fcd5-8fed-4272-8037-453213d8b0d1", trace1.events[0].identityId?.toString())
        assertEquals("Event #2 in Trace #001", trace1.events[1].conceptName)
        assertEquals("0e461b08-4f5e-4aa2-b0a2-b7779c82b119", trace1.events[1].identityId?.toString())

        val trace2 = result.traces[1]
        assertEquals("Trace #002", trace2.conceptName)
        assertEquals("a192d6c5-683b-4188-8f73-222227dd4796", trace2.identityId?.toString())
        assertEquals(2, trace2.events.size)
        assertEquals("Event #1 in Trace #002", trace2.events[0].conceptName)
        assertEquals("1a912b4d-6c78-4a4e-8e0f-219fcea53ea7", trace2.events[0].identityId?.toString())
        assertEquals("Event #2 in Trace #002", trace2.events[1].conceptName)
        assertEquals("8f14c2ec-83eb-4843-9cb4-c45456a5f3cb", trace2.events[1].identityId?.toString())
    }

    @Test
    fun `B1 - parser handles lifecycle and timestamp`() {
        val xes =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xes.features="nested-attributes" openxes.version="1.0RC7" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <extension name="Identity" prefix="identity" uri="http://www.xes-standard.org/identity.xesext"/>
                <extension name="Lifecycle" prefix="lifecycle" uri="http://www.xes-standard.org/lifecycle.xesext"/>
                <extension name="Time" prefix="time" uri="http://www.xes-standard.org/time.xesext"/>
                <string key="concept:name" value="Log concept:name"/>
                <id key="identity:id" value="bbf3f64f-2507-4f0b-a6f8-0113377d69e4"/>
                <string key="lifecycle:model" value="standard"/>
                <trace>
                    <string key="concept:name" value="Trace #001"/>
                    <id key="identity:id" value="ae1a2f41-2d01-479d-b6a3-84f18d790b20"/>
                    <event>
                        <string key="concept:name" value="Event #1 in Trace #001"/>
                        <id key="identity:id" value="1419fcd5-8fed-4272-8037-453213d8b0d1"/>
                        <string key="lifecycle:transition" value="start"/>
                        <date key="time:timestamp" value="2005-01-01T00:00:00.000+01:00"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Event #2 in Trace #001"/>
                        <id key="identity:id" value="0e461b08-4f5e-4aa2-b0a2-b7779c82b119"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2005-01-03T00:00:00.000+01:00"/>
                    </event>
                </trace>
                <trace>
                    <string key="concept:name" value="Trace #002"/>
                    <id key="identity:id" value="a192d6c5-683b-4188-8f73-222227dd4796"/>
                    <event>
                        <string key="concept:name" value="Event #1 in Trace #002"/>
                        <id key="identity:id" value="1a912b4d-6c78-4a4e-8e0f-219fcea53ea7"/>
                        <string key="lifecycle:transition" value="schedule"/>
                        <date key="time:timestamp" value="2005-01-04T00:00:00.000+01:00"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Event #2 in Trace #002"/>
                        <id key="identity:id" value="8f14c2ec-83eb-4843-9cb4-c45456a5f3cb"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2005-01-05T00:00:00.000+01:00"/>
                    </event>
                </trace>
            </log>
            """.trimIndent()

        val result = parse(xes)

        assertEquals(4, result.extensions.size)
        assertEquals("Lifecycle", result.extensions.first { it.prefix == "lifecycle" }.name)
        assertEquals("Time", result.extensions.first { it.prefix == "time" }.name)
        assertEquals("standard", result.lifecycleModel)
        assertEquals(2, result.traces.size)

        val event1 = result.traces[0].events[0]
        assertEquals("start", event1.lifecycleTransition)
        assertEquals(Instant.parse("2004-12-31T23:00:00Z"), event1.timeTimestamp)

        val event2 = result.traces[0].events[1]
        assertEquals("complete", event2.lifecycleTransition)
        assertEquals(Instant.parse("2005-01-02T23:00:00Z"), event2.timeTimestamp)

        val event3 = result.traces[1].events[0]
        assertEquals("schedule", event3.lifecycleTransition)
        assertEquals(Instant.parse("2005-01-03T23:00:00Z"), event3.timeTimestamp)

        val event4 = result.traces[1].events[1]
        assertEquals("complete", event4.lifecycleTransition)
        assertEquals(Instant.parse("2005-01-04T23:00:00Z"), event4.timeTimestamp)
    }

    @Test
    fun `C1 - parser handles organizational attributes`() {
        val xes =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xes.features="nested-attributes" openxes.version="1.0RC7" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <extension name="Identity" prefix="identity" uri="http://www.xes-standard.org/identity.xesext"/>
                <extension name="Organizational" prefix="org" uri="http://www.xes-standard.org/org.xesext"/>
                <extension name="Lifecycle" prefix="lifecycle" uri="http://www.xes-standard.org/lifecycle.xesext"/>
                <extension name="Time" prefix="time" uri="http://www.xes-standard.org/time.xesext"/>
                <string key="concept:name" value="Log concept:name"/>
                <id key="identity:id" value="bbf3f64f-2507-4f0b-a6f8-0113377d69e4"/>
                <string key="lifecycle:model" value="standard"/>
                <trace>
                    <string key="concept:name" value="Trace #001"/>
                    <id key="identity:id" value="ae1a2f41-2d01-479d-b6a3-84f18d790b20"/>
                    <event>
                        <string key="concept:name" value="Event #1 in Trace #001"/>
                        <id key="identity:id" value="1419fcd5-8fed-4272-8037-453213d8b0d1"/>
                        <string key="lifecycle:transition" value="start"/>
                        <date key="time:timestamp" value="2005-01-01T00:00:00.000+01:00"/>
                        <string key="org:group" value="Endoscopy"/>
                        <string key="org:resource" value="Drugs"/>
                        <string key="org:role" value="Intern"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Event #2 in Trace #001"/>
                        <id key="identity:id" value="0e461b08-4f5e-4aa2-b0a2-b7779c82b119"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2005-01-03T00:00:00.000+01:00"/>
                        <string key="org:group" value="Endoscopy"/>
                        <string key="org:resource" value="Pills"/>
                        <string key="org:role" value="Assistant"/>
                    </event>
                </trace>
                <trace>
                    <string key="concept:name" value="Trace #002"/>
                    <id key="identity:id" value="a192d6c5-683b-4188-8f73-222227dd4796"/>
                    <event>
                        <string key="concept:name" value="Event #1 in Trace #002"/>
                        <id key="identity:id" value="1a912b4d-6c78-4a4e-8e0f-219fcea53ea7"/>
                        <string key="lifecycle:transition" value="schedule"/>
                        <date key="time:timestamp" value="2005-01-04T00:00:00.000+01:00"/>
                        <string key="org:group" value="Radiotherapy"/>
                        <string key="org:resource" value="Pills"/>
                        <string key="org:role" value="Intern"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Event #2 in Trace #002"/>
                        <id key="identity:id" value="8f14c2ec-83eb-4843-9cb4-c45456a5f3cb"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2005-01-05T00:00:00.000+01:00"/>
                        <string key="org:group" value="Radiotherapy"/>
                        <string key="org:resource" value="Drugs"/>
                        <string key="org:role" value="Assistant"/>
                    </event>
                </trace>
            </log>
            """.trimIndent()

        val result = parse(xes)

        assertEquals(5, result.extensions.size)
        assertEquals("Organizational", result.extensions.first { it.prefix == "org" }.name)
        assertEquals(2, result.traces.size)

        val events = result.traces.flatMap { it.events }
        assertEquals(4, events.size)
        assertEquals("Endoscopy", events[0].orgGroup)
        assertEquals("Drugs", events[0].orgResource)
        assertEquals("Intern", events[0].orgRole)
        assertEquals("Endoscopy", events[1].orgGroup)
        assertEquals("Pills", events[1].orgResource)
        assertEquals("Assistant", events[1].orgRole)
        assertEquals("Radiotherapy", events[2].orgGroup)
        assertEquals("Pills", events[2].orgResource)
        assertEquals("Intern", events[2].orgRole)
        assertEquals("Radiotherapy", events[3].orgGroup)
        assertEquals("Drugs", events[3].orgResource)
        assertEquals("Assistant", events[3].orgRole)
    }

    @Test
    fun `D1 - parser handles cost attributes`() {
        val xes =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xes.features="nested-attributes" openxes.version="1.0RC7" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <extension name="Identity" prefix="identity" uri="http://www.xes-standard.org/identity.xesext"/>
                <extension name="Lifecycle" prefix="lifecycle" uri="http://www.xes-standard.org/lifecycle.xesext"/>
                <extension name="Time" prefix="time" uri="http://www.xes-standard.org/time.xesext"/>
                <extension name="Organizational" prefix="org" uri="http://www.xes-standard.org/org.xesext"/>
                <extension name="Cost" prefix="cost" uri="http://www.xes-standard.org/cost.xesext"/>
                <string key="concept:name" value="Log concept:name"/>
                <id key="identity:id" value="bbf3f64f-2507-4f0b-a6f8-0113377d69e4"/>
                <string key="lifecycle:model" value="standard"/>
                <trace>
                    <string key="concept:name" value="Trace #001"/>
                    <id key="identity:id" value="ae1a2f41-2d01-479d-b6a3-84f18d790b20"/>
                    <float key="cost:total" value="99.99"/>
                    <string key="cost:currency" value="PLN"/>
                    <event>
                        <string key="concept:name" value="Event #1 in Trace #001"/>
                        <id key="identity:id" value="1419fcd5-8fed-4272-8037-453213d8b0d1"/>
                        <string key="lifecycle:transition" value="start"/>
                        <date key="time:timestamp" value="2005-01-01T00:00:00.000+01:00"/>
                        <string key="org:group" value="Endoscopy"/>
                        <string key="org:resource" value="Drugs"/>
                        <string key="org:role" value="Intern"/>
                        <float key="cost:total" value="90.99"/>
                        <string key="cost:currency" value="PLN"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Event #2 in Trace #001"/>
                        <id key="identity:id" value="0e461b08-4f5e-4aa2-b0a2-b7779c82b119"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2005-01-03T00:00:00.000+01:00"/>
                        <string key="org:group" value="Endoscopy"/>
                        <string key="org:resource" value="Pills"/>
                        <string key="org:role" value="Assistant"/>
                        <float key="cost:total" value="9.00"/>
                        <string key="cost:currency" value="PLN"/>
                    </event>
                </trace>
                <trace>
                    <string key="concept:name" value="Trace #002"/>
                    <id key="identity:id" value="a192d6c5-683b-4188-8f73-222227dd4796"/>
                    <float key="cost:total" value="10.00"/>
                    <string key="cost:currency" value="USD"/>
                    <event>
                        <string key="concept:name" value="Event #1 in Trace #002"/>
                        <id key="identity:id" value="1a912b4d-6c78-4a4e-8e0f-219fcea53ea7"/>
                        <string key="lifecycle:transition" value="schedule"/>
                        <date key="time:timestamp" value="2005-01-04T00:00:00.000+01:00"/>
                        <string key="org:group" value="Radiotherapy"/>
                        <string key="org:resource" value="Pills"/>
                        <string key="org:role" value="Intern"/>
                        <float key="cost:total" value="5.00"/>
                        <string key="cost:currency" value="USD"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Event #2 in Trace #002"/>
                        <id key="identity:id" value="8f14c2ec-83eb-4843-9cb4-c45456a5f3cb"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2005-01-05T00:00:00.000+01:00"/>
                        <string key="org:group" value="Radiotherapy"/>
                        <string key="org:resource" value="Drugs"/>
                        <string key="org:role" value="Assistant"/>
                        <float key="cost:total" value="5.00"/>
                        <string key="cost:currency" value="USD"/>
                    </event>
                </trace>
            </log>
            """.trimIndent()

        val result = parse(xes)

        assertEquals(6, result.extensions.size)
        assertEquals("Cost", result.extensions.first { it.prefix == "cost" }.name)
        assertEquals(2, result.traces.size)

        val trace1 = result.traces[0]
        assertEquals("Trace #001", trace1.conceptName)
        assertEquals("ae1a2f41-2d01-479d-b6a3-84f18d790b20", trace1.identityId?.toString())
        assertEquals(99.99, trace1.costTotal)
        assertEquals("PLN", trace1.costCurrency)
        assertEquals(90.99, trace1.events[0].costTotal)
        assertEquals("PLN", trace1.events[0].costCurrency)
        assertEquals(9.00, trace1.events[1].costTotal)
        assertEquals("PLN", trace1.events[1].costCurrency)

        val trace2 = result.traces[1]
        assertEquals("Trace #002", trace2.conceptName)
        assertEquals("a192d6c5-683b-4188-8f73-222227dd4796", trace2.identityId?.toString())
        assertEquals(10.00, trace2.costTotal)
        assertEquals("USD", trace2.costCurrency)
        assertEquals(5.00, trace2.events[0].costTotal)
        assertEquals("USD", trace2.events[0].costCurrency)
        assertEquals(5.00, trace2.events[1].costTotal)
        assertEquals("USD", trace2.events[1].costCurrency)
    }

    @Test
    fun `X1 - parser handles non-standard extensions and custom attributes`() {
        val xes =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xes.features="nested-attributes" openxes.version="1.0RC7" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <extension name="Identity" prefix="identity" uri="http://www.xes-standard.org/identity.xesext"/>
                <extension name="Lifecycle" prefix="lifecycle" uri="http://www.xes-standard.org/lifecycle.xesext"/>
                <extension name="Time" prefix="time" uri="http://www.xes-standard.org/time.xesext"/>
                <extension name="Organizational" prefix="org" uri="http://www.xes-standard.org/org.xesext"/>
                <extension name="Cost" prefix="wascost" uri="http://www.xes-standard.org/cost.xesext"/>
                <extension name="My own extension" prefix="cost" uri="http://example.com/cost.xesext"/>
                <string key="concept:name" value="Log concept:name"/>
                <id key="identity:id" value="bbf3f64f-2507-4f0b-a6f8-0113377d69e4"/>
                <string key="lifecycle:model" value="standard"/>
                <string key="value-without-extension" value="some-special-value"/>
                <trace>
                    <string key="concept:name" value="Trace #001"/>
                    <id key="identity:id" value="ae1a2f41-2d01-479d-b6a3-84f18d790b20"/>
                    <float key="wascost:total" value="99.99"/>
                    <string key="wascost:currency" value="PLN"/>
                    <event>
                        <string key="concept:name" value="Event #1 in Trace #001"/>
                        <id key="identity:id" value="1419fcd5-8fed-4272-8037-453213d8b0d1"/>
                        <string key="lifecycle:transition" value="start"/>
                        <date key="time:timestamp" value="2005-01-01T00:00:00.000+01:00"/>
                        <string key="org:group" value="Endoscopy"/>
                        <string key="org:resource" value="Drugs"/>
                        <string key="org:role" value="Intern"/>
                        <float key="wascost:total" value="90.99"/>
                        <string key="wascost:currency" value="PLN"/>
                        <int key="cost:level" value="1"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Event #2 in Trace #001"/>
                        <id key="identity:id" value="0e461b08-4f5e-4aa2-b0a2-b7779c82b119"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2005-01-03T00:00:00.000+01:00"/>
                        <string key="org:group" value="Endoscopy"/>
                        <string key="org:resource" value="Pills"/>
                        <string key="org:role" value="Assistant"/>
                        <float key="wascost:total" value="9.00"/>
                        <string key="wascost:currency" value="PLN"/>
                        <int key="cost:level" value="2"/>
                    </event>
                </trace>
                <trace>
                    <string key="concept:name" value="Trace #002"/>
                    <id key="identity:id" value="a192d6c5-683b-4188-8f73-222227dd4796"/>
                    <float key="wascost:total" value="10.00"/>
                    <string key="wascost:currency" value="USD"/>
                    <event>
                        <string key="concept:name" value="Event #1 in Trace #002"/>
                        <id key="identity:id" value="1a912b4d-6c78-4a4e-8e0f-219fcea53ea7"/>
                        <string key="lifecycle:transition" value="schedule"/>
                        <date key="time:timestamp" value="2005-01-04T00:00:00.000+01:00"/>
                        <string key="org:group" value="Radiotherapy"/>
                        <string key="org:resource" value="Pills"/>
                        <string key="org:role" value="Intern"/>
                        <float key="wascost:total" value="5.00"/>
                        <string key="wascost:currency" value="USD"/>
                        <int key="cost:level" value="1"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Event #2 in Trace #002"/>
                        <id key="identity:id" value="8f14c2ec-83eb-4843-9cb4-c45456a5f3cb"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2005-01-05T00:00:00.000+01:00"/>
                        <string key="org:group" value="Radiotherapy"/>
                        <string key="org:resource" value="Drugs"/>
                        <string key="org:role" value="Assistant"/>
                        <float key="wascost:total" value="5.00"/>
                        <string key="wascost:currency" value="USD"/>
                        <int key="cost:level" value="1"/>
                    </event>
                </trace>
            </log>
            """.trimIndent()

        val result = parse(xes)

        assertEquals(7, result.extensions.size)
        assertEquals("Concept", result.extensions.first { it.prefix == "concept" }.name)
        assertEquals("Identity", result.extensions.first { it.prefix == "identity" }.name)
        assertEquals("Lifecycle", result.extensions.first { it.prefix == "lifecycle" }.name)
        assertEquals("Time", result.extensions.first { it.prefix == "time" }.name)
        assertEquals("Organizational", result.extensions.first { it.prefix == "org" }.name)
        assertEquals("Cost", result.extensions.first { it.prefix == "wascost" }.name)
        assertEquals("My own extension", result.extensions.first { it.prefix == "cost" }.name)

        assertEquals("Log concept:name", result.conceptName)
        assertEquals("bbf3f64f-2507-4f0b-a6f8-0113377d69e4", result.identityId?.toString())
        assertEquals("standard", result.lifecycleModel)
        assertEquals("some-special-value", result.customAttributes["value-without-extension"])
        assertEquals(2, result.traces.size)

        val trace1 = result.traces[0]
        assertEquals("Trace #001", trace1.conceptName)
        assertEquals("ae1a2f41-2d01-479d-b6a3-84f18d790b20", trace1.identityId?.toString())
        assertEquals(99.99, trace1.costTotal)
        assertEquals("PLN", trace1.costCurrency)
        assertEquals(2, trace1.events.size)

        val trace1Event1 = trace1.events[0]
        assertEquals("Event #1 in Trace #001", trace1Event1.conceptName)
        assertEquals("1419fcd5-8fed-4272-8037-453213d8b0d1", trace1Event1.identityId?.toString())
        assertEquals("start", trace1Event1.lifecycleTransition)
        assertEquals(Instant.parse("2004-12-31T23:00:00Z"), trace1Event1.timeTimestamp)
        assertEquals("Endoscopy", trace1Event1.orgGroup)
        assertEquals("Drugs", trace1Event1.orgResource)
        assertEquals("Intern", trace1Event1.orgRole)
        assertEquals(90.99, trace1Event1.costTotal)
        assertEquals("PLN", trace1Event1.costCurrency)
        assertEquals(1, trace1Event1.customAttributes["cost:level"])

        val trace1Event2 = trace1.events[1]
        assertEquals("Event #2 in Trace #001", trace1Event2.conceptName)
        assertEquals("0e461b08-4f5e-4aa2-b0a2-b7779c82b119", trace1Event2.identityId?.toString())
        assertEquals("complete", trace1Event2.lifecycleTransition)
        assertEquals(Instant.parse("2005-01-02T23:00:00Z"), trace1Event2.timeTimestamp)
        assertEquals("Endoscopy", trace1Event2.orgGroup)
        assertEquals("Pills", trace1Event2.orgResource)
        assertEquals("Assistant", trace1Event2.orgRole)
        assertEquals(9.00, trace1Event2.costTotal)
        assertEquals("PLN", trace1Event2.costCurrency)
        assertEquals(2, trace1Event2.customAttributes["cost:level"])

        val trace2 = result.traces[1]
        assertEquals("Trace #002", trace2.conceptName)
        assertEquals("a192d6c5-683b-4188-8f73-222227dd4796", trace2.identityId?.toString())
        assertEquals(10.00, trace2.costTotal)
        assertEquals("USD", trace2.costCurrency)
        assertEquals(2, trace2.events.size)

        val trace2Event1 = trace2.events[0]
        assertEquals("Event #1 in Trace #002", trace2Event1.conceptName)
        assertEquals("1a912b4d-6c78-4a4e-8e0f-219fcea53ea7", trace2Event1.identityId?.toString())
        assertEquals("schedule", trace2Event1.lifecycleTransition)
        assertEquals(Instant.parse("2005-01-03T23:00:00Z"), trace2Event1.timeTimestamp)
        assertEquals("Radiotherapy", trace2Event1.orgGroup)
        assertEquals("Pills", trace2Event1.orgResource)
        assertEquals("Intern", trace2Event1.orgRole)
        assertEquals(5.00, trace2Event1.costTotal)
        assertEquals("USD", trace2Event1.costCurrency)
        assertEquals(1, trace2Event1.customAttributes["cost:level"])

        val trace2Event2 = trace2.events[1]
        assertEquals("Event #2 in Trace #002", trace2Event2.conceptName)
        assertEquals("8f14c2ec-83eb-4843-9cb4-c45456a5f3cb", trace2Event2.identityId?.toString())
        assertEquals("complete", trace2Event2.lifecycleTransition)
        assertEquals(Instant.parse("2005-01-04T23:00:00Z"), trace2Event2.timeTimestamp)
        assertEquals("Radiotherapy", trace2Event2.orgGroup)
        assertEquals("Drugs", trace2Event2.orgResource)
        assertEquals("Assistant", trace2Event2.orgRole)
        assertEquals(5.00, trace2Event2.costTotal)
        assertEquals("USD", trace2Event2.costCurrency)
        assertEquals(1, trace2Event2.customAttributes["cost:level"])
    }

    @Test
    @Disabled("Local broad parser scenario, not present in ProcessM XESImportCertificationFirstLevelTest; keep disabled until moved out of processm compatibility package or rewritten as exact port.")
    fun `full certification - parser handles all standard and custom attributes together`() {
        val xes =
            """
            <?xml version="1.0" encoding="UTF-8" ?>
            <log xes.version="1.0" xes.features="nested-attributes" xmlns="http://www.xes-standard.org/">
                <extension name="Concept" prefix="concept" uri="http://www.xes-standard.org/concept.xesext"/>
                <extension name="Identity" prefix="identity" uri="http://www.xes-standard.org/identity.xesext"/>
                <extension name="Lifecycle" prefix="lifecycle" uri="http://www.xes-standard.org/lifecycle.xesext"/>
                <extension name="Time" prefix="time" uri="http://www.xes-standard.org/time.xesext"/>
                <extension name="Organizational" prefix="org" uri="http://www.xes-standard.org/org.xesext"/>
                <extension name="Cost" prefix="cost" uri="http://www.xes-standard.org/cost.xesext"/>
                <global scope="trace">
                    <string key="concept:name" value="__INVALID__"/>
                </global>
                <global scope="event">
                    <string key="concept:name" value="__INVALID__"/>
                    <string key="lifecycle:transition" value="complete"/>
                    <date key="time:timestamp" value="1970-01-01T00:00:00.000+00:00"/>
                </global>
                <classifier name="Event Name" keys="concept:name"/>
                <classifier name="Activity+Lifecycle" keys="concept:name lifecycle:transition"/>
                <string key="concept:name" value="Full Certification Log"/>
                <id key="identity:id" value="aaaa-bbbb-cccc-dddd"/>
                <string key="lifecycle:model" value="standard"/>
                <string key="source" value="Test"/>
                <int key="version" value="1"/>
                <trace>
                    <string key="concept:name" value="Case-Full-001"/>
                    <id key="identity:id" value="trace-id-001"/>
                    <float key="cost:total" value="1234.56"/>
                    <string key="cost:currency" value="USD"/>
                    <string key="department" value="HR"/>
                    <event>
                        <string key="concept:name" value="Submit Request"/>
                        <id key="identity:id" value="event-id-001"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2023-06-01T09:00:00.000+02:00"/>
                        <string key="org:resource" value="Alice"/>
                        <string key="org:role" value="Employee"/>
                        <string key="org:group" value="HR"/>
                        <float key="cost:total" value="10.00"/>
                        <string key="cost:currency" value="USD"/>
                        <string key="channel" value="web"/>
                        <int key="retries" value="0"/>
                    </event>
                    <event>
                        <string key="concept:name" value="Approve"/>
                        <id key="identity:id" value="event-id-002"/>
                        <string key="lifecycle:transition" value="complete"/>
                        <date key="time:timestamp" value="2023-06-01T14:00:00.000+02:00"/>
                        <string key="org:resource" value="Bob"/>
                        <string key="org:role" value="Manager"/>
                        <string key="org:group" value="HR"/>
                        <float key="cost:total" value="0.00"/>
                        <string key="cost:currency" value="USD"/>
                        <boolean key="overridden" value="false"/>
                    </event>
                </trace>
            </log>
            """.trimIndent()

        val result = parse(xes)

        assertEquals("Full Certification Log", result.conceptName)
        assertEquals("aaaa-bbbb-cccc-dddd", result.customAttributes["identity:id"])
        assertEquals("standard", result.lifecycleModel)

        val trace = result.traces[0]
        assertEquals("Case-Full-001", trace.conceptName)
        assertEquals("trace-id-001", trace.customAttributes["identity:id"])
        assertEquals(1234.56, trace.costTotal)
        assertEquals("USD", trace.costCurrency)
        assertEquals("HR", trace.customAttributes["department"])

        val event1 = trace.events[0]
        assertEquals("Submit Request", event1.conceptName)
        assertEquals("event-id-001", event1.customAttributes["identity:id"])
        assertEquals("complete", event1.lifecycleTransition)
        assertNotNull(event1.timeTimestamp)
        assertEquals("Alice", event1.orgResource)
        assertEquals("Employee", event1.orgRole)
        assertEquals("HR", event1.orgGroup)
        assertEquals(10.00, event1.costTotal)
        assertEquals("web", event1.customAttributes["channel"])
        assertEquals(0, event1.customAttributes["retries"])

        val event2 = trace.events[1]
        assertEquals("Approve", event2.conceptName)
        assertEquals("Bob", event2.orgResource)
        assertEquals(0.00, event2.costTotal)
        assertEquals(false, event2.customAttributes["overridden"])
    }

    private fun parse(xml: String) = parser.parseXesLog(xml.byteInputStream())
}
