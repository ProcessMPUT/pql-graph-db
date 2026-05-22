package com.processm.processminterpreter.domain.pql.semantics

import com.processm.processminterpreter.domain.pql.syntax.RawLiteral
import com.processm.processminterpreter.domain.pql.syntax.RawLiteralKind
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.catalog.Type
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class LiteralTyperTest {
    private val loc = SourceLocation.UNKNOWN

    @Test
    fun `date-only D literal is typed as start of day datetime`() {
        val typed = LiteralTyper.type(RawLiteral("D2007-01-01", RawLiteralKind.DATETIME, loc))

        assertEquals(Type.DATETIME, typed.type)
        assertEquals(LocalDateTime.of(2007, 1, 1, 0, 0), typed.value)
    }

    @Test
    fun `month-only D literal is typed as first day of month`() {
        val typed = LiteralTyper.type(RawLiteral("D2007-01", RawLiteralKind.DATETIME, loc))

        assertEquals(Type.DATETIME, typed.type)
        assertEquals(LocalDateTime.of(2007, 1, 1, 0, 0), typed.value)
    }

    @Test
    fun `compact date D literal is typed as start of day datetime`() {
        val typed = LiteralTyper.type(RawLiteral("D20070101", RawLiteralKind.DATETIME, loc))

        assertEquals(Type.DATETIME, typed.type)
        assertEquals(LocalDateTime.of(2007, 1, 1, 0, 0), typed.value)
    }
}
