package com.processm.processminterpreter.processm.querylanguage

import com.processm.processminterpreter.pql.model.Attribute
import com.processm.processminterpreter.pql.model.DateTimeLiteral
import com.processm.processminterpreter.pql.model.FunctionType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.processm.processminterpreter.pql.model.Function as PQLFunction

/**
 * Function Tests - 1:1 copy of original ProcessM FunctionTests.kt
 *
 * Source: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/querylanguage/FunctionTests.kt
 */
class FunctionTests {
    @Test
    fun validScalarFunctionTest() {
        val dateTime = DateTimeLiteral.parse("D2020-03-26", 0, 0)
        val function = PQLFunction("year", 0, 0, dateTime)
        assertEquals(FunctionType.Scalar, function.functionType)
        assertEquals("year", function.name)
        assertEquals(1, function.children.size)
    }

    @Test
    fun validAggregateFunctionTest() {
        val attr = Attribute("e:total", 0, 0)
        val function = PQLFunction("avg", 0, 0, attr)
        assertEquals(FunctionType.Aggregation, function.functionType)
        assertEquals("avg", function.name)
        assertEquals(1, function.children.size)
    }

    @Test
    fun invalidFunctionTest() {
        assertFailsWith<IllegalArgumentException> { PQLFunction("XYZ", 0, 0) }
        assertFailsWith<IllegalArgumentException> { PQLFunction("avg", 0, 0) }
        assertFailsWith<IllegalArgumentException> { PQLFunction("year", 0, 0) }
    }
}
