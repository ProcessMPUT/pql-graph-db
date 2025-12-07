package com.processm.processminterpreter.pql.original

import com.processm.processminterpreter.pql.model.Scope
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("PQL")
class ScopeTests {

    @Test
    fun testScopeHierarchy() {
        // Test upper hierarchy
        assertNull(Scope.LOG.upper)
        assertEquals(Scope.LOG, Scope.TRACE.upper)
        assertEquals(Scope.TRACE, Scope.EVENT.upper)

        // Test lower hierarchy
        assertEquals(Scope.TRACE, Scope.LOG.lower)
        assertEquals(Scope.EVENT, Scope.TRACE.lower)
        assertNull(Scope.EVENT.lower)
    }

    @Test
    fun testScopeParsing() {
        assertEquals(Scope.LOG, Scope.parse("log"))
        assertEquals(Scope.LOG, Scope.parse("LOG"))
        assertEquals(Scope.LOG, Scope.parse("l"))
        assertEquals(Scope.LOG, Scope.parse("L"))

        assertEquals(Scope.TRACE, Scope.parse("trace"))
        assertEquals(Scope.TRACE, Scope.parse("TRACE"))
        assertEquals(Scope.TRACE, Scope.parse("t"))
        assertEquals(Scope.TRACE, Scope.parse("T"))

        assertEquals(Scope.EVENT, Scope.parse("event"))
        assertEquals(Scope.EVENT, Scope.parse("EVENT"))
        assertEquals(Scope.EVENT, Scope.parse("e"))
        assertEquals(Scope.EVENT, Scope.parse("E"))
    }

    @Test
    fun testInvalidScopeParsing() {
        assertThrows(IllegalArgumentException::class.java) {
            Scope.parse("invalid")
        }
        
        assertThrows(IllegalArgumentException::class.java) {
            Scope.parse("")
        }
    }
    
    @Test
    fun testDefaultScopeParsing() {
        assertEquals(Scope.EVENT, Scope.parse("invalid", Scope.EVENT))
        assertEquals(Scope.LOG, Scope.parse("invalid", Scope.LOG))
        assertEquals(Scope.TRACE, Scope.parse("invalid", Scope.TRACE))
    }
    
    @Test
    fun lowerAndUpperTest() {
        val scopes = arrayOf(Scope.LOG, Scope.TRACE, Scope.EVENT)
        for (scope in scopes) {
            assertEquals(scope, scope.lower?.upper ?: Scope.EVENT)
            assertEquals(scope, scope.upper?.lower ?: Scope.LOG)
        }
    }
}
