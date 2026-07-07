package com.processm.processminterpreter.pql.cypher

import com.processm.processminterpreter.pql.catalog.AttributeKind
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.catalog.Type
import com.processm.processminterpreter.pql.ast.PqlExpression
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhysicalAttributeMapperTest {
    private val mapper = PhysicalAttributeMapper()
    private val loc = SourceLocation(1, 0)

    private fun std(scope: Scope, xesName: String) = PqlExpression.Attribute(
        name = xesName, baseScope = scope, effectiveScope = scope,
        kind = AttributeKind.STANDARD, xesStandardName = xesName,
        wasBracketed = false, type = Type.STRING, location = loc,
    )

    private fun custom(scope: Scope, name: String) = PqlExpression.Attribute(
        name = name, baseScope = scope, effectiveScope = scope,
        kind = AttributeKind.CUSTOM, xesStandardName = null,
        wasBracketed = true, type = Type.STRING, location = loc,
    )

    private fun system(scope: Scope, name: String) = PqlExpression.Attribute(
        name = name, baseScope = scope, effectiveScope = scope,
        kind = AttributeKind.SYSTEM, xesStandardName = null,
        wasBracketed = false, type = Type.ID, location = loc,
    )

    @Test
    fun `event concept_name maps to event dot activity`() {
        val ref = mapper.map(std(Scope.EVENT, "concept:name"), "event")
        assertEquals("event", ref.nodeVar)
        assertEquals("activity", ref.property)
        assertFalse(ref.requiresBackticks)
        assertEquals("event.activity", ref.toCypher())
    }

    @Test
    fun `embedded backtick in a custom attribute name is doubled, not an identifier break`() {
        val ref = mapper.map(custom(Scope.EVENT, "a`b RETURN 1 //"), "event")
        assertTrue(ref.requiresBackticks)
        assertEquals("event.`a``b RETURN 1 //`", ref.toCypher())
        assertEquals("`a``b RETURN 1 //`", cypherMapKey(ref))
    }

    @Test
    fun `event cost_total maps to event dot cost`() {
        val ref = mapper.map(std(Scope.EVENT, "cost:total"), "event")
        assertEquals("cost", ref.property)
        assertFalse(ref.requiresBackticks)
    }

    @Test
    fun `event cost_currency keeps colon and requires backticks`() {
        val ref = mapper.map(std(Scope.EVENT, "cost:currency"), "event")
        assertEquals("cost:currency", ref.property)
        assertTrue(ref.requiresBackticks)
        assertEquals("event.`cost:currency`", ref.toCypher())
    }

    @Test
    fun `trace cost_total keeps colon and requires backticks`() {
        val ref = mapper.map(std(Scope.TRACE, "cost:total"), "trace")
        assertEquals("cost:total", ref.property)
        assertTrue(ref.requiresBackticks)
    }

    @Test
    fun `trace concept_name maps to caseId`() {
        val ref = mapper.map(std(Scope.TRACE, "concept:name"), "trace")
        assertEquals("caseId", ref.property)
    }

    @Test
    fun `log concept_name maps to name`() {
        val ref = mapper.map(std(Scope.LOG, "concept:name"), "log")
        assertEquals("name", ref.property)
    }

    @Test
    fun `log identity_id maps to XES identity property`() {
        val ref = mapper.map(std(Scope.LOG, "identity:id"), "log")
        assertEquals("identity:id", ref.property)
        assertTrue(ref.requiresBackticks)
        assertEquals("log.`identity:id`", ref.toCypher())
    }

    @Test
    fun `logId system attribute maps to physical logId property`() {
        val ref = mapper.map(system(Scope.LOG, "logId"), "log")
        assertEquals("logId", ref.property)
        assertFalse(ref.requiresBackticks)
        assertEquals("log.logId", ref.toCypher())
    }

    @Test
    fun `event time_timestamp maps to timestamp`() {
        val ref = mapper.map(std(Scope.EVENT, "time:timestamp"), "event")
        assertEquals("timestamp", ref.property)
    }

    @Test
    fun `custom attribute with colon requires backticks`() {
        val ref = mapper.map(custom(Scope.EVENT, "my:custom"), "event")
        assertEquals("my:custom", ref.property)
        assertTrue(ref.requiresBackticks)
    }

    @Test
    fun `custom attribute without colon uses bare property`() {
        val ref = mapper.map(custom(Scope.EVENT, "extraInfo"), "event")
        assertEquals("extraInfo", ref.property)
        assertFalse(ref.requiresBackticks)
    }

    @Test
    fun `unknown standard attribute falls through to xes name`() {
        // e.g. time:timestamp at LOG scope is not in our table - fallback to xes name
        val ref = mapper.map(std(Scope.LOG, "time:timestamp"), "log")
        assertEquals("time:timestamp", ref.property)
        assertTrue(ref.requiresBackticks)
    }

    @Test
    fun `classifier attribute keeps raw name`() {
        val attr = PqlExpression.Attribute(
            name = "c:activity", baseScope = Scope.EVENT, effectiveScope = Scope.EVENT,
            kind = AttributeKind.CLASSIFIER, xesStandardName = null,
            wasBracketed = false, type = Type.STRING, location = loc,
        )
        val ref = mapper.map(attr, "event")
        assertEquals("c:activity", ref.property)
        assertTrue(ref.requiresBackticks)
    }
}
