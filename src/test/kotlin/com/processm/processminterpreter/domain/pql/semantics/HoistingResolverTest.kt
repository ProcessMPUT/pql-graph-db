package com.processm.processminterpreter.domain.pql.semantics

import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.error.InvalidScopeHoistingException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class HoistingResolverTest {
    private val loc = SourceLocation(1, 0)

    @Test
    fun `zero hoisting returns base scope`() {
        assertEquals(Scope.EVENT, HoistingResolver.apply(Scope.EVENT, 0, loc))
        assertEquals(Scope.TRACE, HoistingResolver.apply(Scope.TRACE, 0, loc))
        assertEquals(Scope.LOG, HoistingResolver.apply(Scope.LOG, 0, loc))
    }

    @Test
    fun `event hoisted once becomes trace`() {
        assertEquals(Scope.TRACE, HoistingResolver.apply(Scope.EVENT, 1, loc))
    }

    @Test
    fun `event hoisted twice becomes log`() {
        assertEquals(Scope.LOG, HoistingResolver.apply(Scope.EVENT, 2, loc))
    }

    @Test
    fun `trace hoisted once becomes log`() {
        assertEquals(Scope.LOG, HoistingResolver.apply(Scope.TRACE, 1, loc))
    }

    @Test
    fun `log hoisted throws`() {
        assertThrows(InvalidScopeHoistingException::class.java) {
            HoistingResolver.apply(Scope.LOG, 1, loc)
        }
    }

    @Test
    fun `trace hoisted twice throws`() {
        assertThrows(InvalidScopeHoistingException::class.java) {
            HoistingResolver.apply(Scope.TRACE, 2, loc)
        }
    }

    @Test
    fun `negative hoisting throws IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            HoistingResolver.apply(Scope.EVENT, -1, loc)
        }
    }
}
