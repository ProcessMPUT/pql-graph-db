package com.processm.processminterpreter.domain.pql.syntax

import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.error.PQLSyntaxException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AttributeReferenceParserTest {
    private val loc = SourceLocation(1, 0)

    @Test
    fun `plain name, no scope, no hoisting`() {
        val r = AttributeReferenceParser.parse("name", loc)
        assertEquals("name", r.name)
        assertNull(r.scopeHint)
        assertEquals(0, r.hoisting)
        assertFalse(r.wasBracketed)
    }

    @Test
    fun `scoped attribute with shorthand`() {
        val r = AttributeReferenceParser.parse("e:name", loc)
        assertEquals("name", r.name)
        assertEquals("e", r.scopeHint)
        assertEquals(0, r.hoisting)
    }

    @Test
    fun `scoped attribute with full scope name`() {
        val r = AttributeReferenceParser.parse("event:name", loc)
        assertEquals("event", r.scopeHint)
    }

    @Test
    fun `single hoisting`() {
        val r = AttributeReferenceParser.parse("^e:name", loc)
        assertEquals(1, r.hoisting)
        assertEquals("e", r.scopeHint)
        assertEquals("name", r.name)
    }

    @Test
    fun `double hoisting`() {
        val r = AttributeReferenceParser.parse("^^e:timestamp", loc)
        assertEquals(2, r.hoisting)
    }

    @Test
    fun `bracketed custom attribute`() {
        val r = AttributeReferenceParser.parse("[e:customAttr]", loc)
        assertTrue(r.wasBracketed)
        assertEquals("e", r.scopeHint)
        assertEquals("customAttr", r.name)
    }

    @Test
    fun `bracketed with spaces allowed`() {
        val r = AttributeReferenceParser.parse("[trace:name with spaces]", loc)
        assertTrue(r.wasBracketed)
        assertEquals("trace", r.scopeHint)
        assertEquals("name with spaces", r.name)
    }

    @Test
    fun `multi-part xes name like org colon group keeps colons in name`() {
        val r = AttributeReferenceParser.parse("e:org:group", loc)
        assertEquals("e", r.scopeHint)
        assertEquals("org:group", r.name)
    }

    @Test
    fun `unrecognized scope prefix becomes part of name`() {
        val r = AttributeReferenceParser.parse("org:group", loc)
        assertNull(r.scopeHint)
        assertEquals("org:group", r.name)
    }

    @Test
    fun `blank attribute throws`() {
        assertThrows<PQLSyntaxException> { AttributeReferenceParser.parse("", loc) }
    }

    @Test
    fun `unbracketed space in name throws`() {
        assertThrows<PQLSyntaxException> { AttributeReferenceParser.parse("e:name with space", loc) }
    }
}
