package com.processm.processminterpreter.processm.querylanguage

import com.processm.processminterpreter.pql.model.Scope
import com.processm.processminterpreter.pql.model.StringLiteral
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Literal Tests - 1:1 copy of original ProcessM LiteralTests.kt
 *
 * Source: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/querylanguage/LiteralTests.kt
 */
class LiteralTests {

    @Test
    fun emptyStringTest() {
        val literal = StringLiteral.parse("\"\"", 0, 0)

        assertNull(literal.scope)
        assertEquals(Scope.Event, literal.effectiveScope)
        assertTrue(literal.isTerminal)
        assertEquals("", literal.value)
    }

    @Test
    fun escapeCharInStringTest() {
        val literal = StringLiteral.parse("\"abc jr\\\"\"", 0, 0)

        assertNull(literal.scope)
        assertEquals(Scope.Event, literal.effectiveScope)
        assertTrue(literal.isTerminal)
        assertEquals("abc jr\"", literal.value)
    }

    @Test
    fun escapeSequenceAtTheEndOfStringTest() {
        val literal = StringLiteral.parse("\"abc jr\\\"", 0, 0)

        assertNull(literal.scope)
        assertEquals(Scope.Event, literal.effectiveScope)
        assertTrue(literal.isTerminal)
        assertEquals("abc jr", literal.value)
    }

    @Test
    fun specialEscapeCharactersTest() {
        val literal = StringLiteral.parse("\"\\t\\r\\n\\f\\b\\\\\"", 0, 0)

        assertNull(literal.scope)
        assertEquals(Scope.Event, literal.effectiveScope)
        assertTrue(literal.isTerminal)
        assertEquals("\t\r\n" + 12.toChar() + "\b\\", literal.value)
    }
}
