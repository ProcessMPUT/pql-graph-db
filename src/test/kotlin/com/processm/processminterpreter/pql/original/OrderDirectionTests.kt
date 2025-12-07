package com.processm.processminterpreter.pql.original

import com.processm.processminterpreter.pql.model.OrderDirection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class OrderDirectionTests {
    @Test
    fun parseTest() {
        val directions = arrayOf(OrderDirection.ASCENDING, OrderDirection.DESCENDING)
        for (direction in directions) {
            assertEquals(direction, OrderDirection.parse(direction.toString()))
            assertEquals(direction, OrderDirection.parse(direction.name))
        }
    }

    @Test
    fun invalidParseTest() {
        assertThrows(IllegalArgumentException::class.java) { OrderDirection.parse("XYZ") }
    }
}
