package com.processm.processminterpreter.infrastructure.parser.antlr

import com.processm.processminterpreter.domain.pql.error.PQLSyntaxException
import com.processm.processminterpreter.domain.pql.error.Problem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PQLErrorListenerTest {

    @Test
    fun `starts with no errors`() {
        val l = PQLErrorListener()
        assertFalse(l.hasErrors)
        assertTrue(l.errors.isEmpty())
        l.throwIfAny() // no-op
    }

    @Test
    fun `syntaxError accumulates one PQLSyntaxException with location and message`() {
        val l = PQLErrorListener()
        l.syntaxError(null, null, 3, 7, "unexpected token", null)

        assertTrue(l.hasErrors)
        assertEquals(1, l.errors.size)
        val err = l.errors[0]
        assertEquals(Problem.SyntaxError, err.problem)
        assertEquals(3, err.location.line)
        assertEquals(7, err.location.charPositionInLine)
    }

    @Test
    fun `syntaxError handles null message with default text`() {
        val l = PQLErrorListener()
        l.syntaxError(null, null, 1, 0, null, null)
        assertEquals(1, l.errors.size)
    }

    @Test
    fun `multiple errors are collected in order`() {
        val l = PQLErrorListener()
        l.syntaxError(null, null, 1, 0, "first", null)
        l.syntaxError(null, null, 2, 5, "second", null)
        l.syntaxError(null, null, 3, 9, "third", null)

        assertEquals(3, l.errors.size)
        assertEquals(1, l.errors[0].location.line)
        assertEquals(2, l.errors[1].location.line)
        assertEquals(3, l.errors[2].location.line)
    }

    @Test
    fun `throwIfAny throws the first accumulated error`() {
        val l = PQLErrorListener()
        l.syntaxError(null, null, 4, 2, "boom", null)
        l.syntaxError(null, null, 9, 1, "later", null)
        val thrown = assertThrows<PQLSyntaxException> { l.throwIfAny() }
        assertEquals(4, thrown.location.line)
    }
}
