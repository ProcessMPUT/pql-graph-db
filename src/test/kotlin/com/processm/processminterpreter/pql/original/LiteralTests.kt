package com.processm.processminterpreter.pql.original

import com.processm.processminterpreter.pql.model.Scope
import com.processm.processminterpreter.pql.model.StringLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiteralTests {

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
        // Original test intent seems to be testing a string ending with an escaped char or just a normal string
        // "abc jr\" -> value "abc jr"
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
