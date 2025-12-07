package com.processm.processminterpreter.pql.original

import com.processm.processminterpreter.pql.model.Attribute
import com.processm.processminterpreter.pql.model.DateTimeLiteral
import com.processm.processminterpreter.pql.model.Function
import com.processm.processminterpreter.pql.model.FunctionType
import com.processm.processminterpreter.pql.model.InvalidFunctionException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FunctionProcessMTests {

    @Test
    fun validScalarFunctionTest() {
        val function = Function("year", 0, 0, DateTimeLiteral.parse("D2020-03-26", 0, 0))
        assertEquals(FunctionType.SCALAR, function.functionType)
        assertEquals("year", function.name)
        assertEquals(1, function.children.size)
    }

    @Test
    fun validAggregateFunctionTest() {
        val function = Function("avg", 0, 0, Attribute("e:total", 0, 0))
        assertEquals(FunctionType.AGGREGATION, function.functionType)
        assertEquals("avg", function.name)
        assertEquals(1, function.children.size)
    }

    @Test
    fun invalidFunctionTest() {
        assertThrows(InvalidFunctionException::class.java) { Function("XYZ", 0, 0) }
        assertThrows(InvalidFunctionException::class.java) { Function("avg", 0, 0) }
        assertThrows(InvalidFunctionException::class.java) { Function("year", 0, 0) }
    }
}
