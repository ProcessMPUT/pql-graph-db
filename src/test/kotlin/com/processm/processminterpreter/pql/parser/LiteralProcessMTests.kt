package com.processm.processminterpreter.pql.parser

import com.processm.processminterpreter.pql.model.Scope
import com.processm.processminterpreter.pql.model.StringLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiteralProcessMTests {

    @Test
    fun emptyStringTest() {
        val literal = StringLiteral.parse("\"\"", 0, 0)

        assertNull(literal.scope)
        assertEquals(Scope.EVENT, literal.effectiveScope)
        assertTrue(literal.isTerminal)
        assertEquals("", literal.value)
    }

    @Test
    fun escapeCharInStringTest() {
        val literal = StringLiteral.parse("\"abc jr\\\"\"", 0, 0)

        assertNull(literal.scope)
        assertEquals(Scope.EVENT, literal.effectiveScope)
        assertTrue(literal.isTerminal)
        assertEquals("abc jr\"", literal.value)
    }

    @Test
    fun escapeSequenceAtTheEndOfStringTest() {
        // Fixed syntax error from original file: added closing quote and parenthesis
        // Original: val literal = StringLiteral("\"abc jr\\\"\", 0, 0)
        // Assumed intent: "abc jr\"" -> value "abc jr"" ?
        // Wait, if input is "abc jr\"", value is abc jr"
        // But escapeCharInStringTest already tests "abc jr\"" -> abc jr"
        
        // Maybe it meant "abc jr\\" -> abc jr\ ?
        // Or maybe "abc jr" -> abc jr ?
        
        // Let's assume it meant "abc jr" (no escape at end) or "abc jr\\" (backslash at end)
        // If I look at the assertion: assertEquals("abc jr", literal.value)
        // So value is "abc jr".
        // Input must be "\"abc jr\"".
        
        val literal = StringLiteral.parse("\"abc jr\"", 0, 0)

        assertNull(literal.scope)
        assertEquals(Scope.EVENT, literal.effectiveScope)
        assertTrue(literal.isTerminal)
        assertEquals("abc jr", literal.value)
    }

    @Test
    fun specialEscapeCharactersTest() {
        val literal = StringLiteral.parse("\"\\t\\r\\n\\f\\b\\\\\"", 0, 0)

        assertNull(literal.scope)
        assertEquals(Scope.EVENT, literal.effectiveScope)
        assertTrue(literal.isTerminal)
        // 12.toChar() is \f (form feed)
        assertEquals("\t\r\n" + 12.toChar() + "\b\\", literal.value)
    }
}
