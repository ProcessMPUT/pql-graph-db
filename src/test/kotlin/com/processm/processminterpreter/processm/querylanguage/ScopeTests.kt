package com.processm.processminterpreter.processm.querylanguage

import com.processm.processminterpreter.pql.model.Scope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Scope Tests - 1:1 copy of original ProcessM ScopeTests.kt
 *
 * Source: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/querylanguage/ScopeTests.kt
 */
class ScopeTests {
    @Test
    fun parseTest() {
        val scopes = arrayOf(Scope.Log, Scope.Trace, Scope.Event)
        for (scope in scopes) {
            assertEquals(scope, Scope.parse(scope.toString()))
            assertEquals(scope, Scope.parse(scope.name))
            assertEquals(scope, Scope.parse(scope.shortName))
        }
    }

    @Test
    fun invalidParseTest() {
        assertFailsWith<IllegalArgumentException> { Scope.parse("XYZ") }
    }

    @Test
    fun lowerAndUpperTest() {
        val scopes = arrayOf(Scope.Log, Scope.Trace, Scope.Event)
        for (scope in scopes) {
            assertEquals(scope, scope.lower?.upper ?: Scope.Event)
            assertEquals(scope, scope.upper?.lower ?: Scope.Log)
        }
    }
}
