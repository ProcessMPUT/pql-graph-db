package com.processm.processminterpreter.processm.querylanguage

import com.processm.processminterpreter.pql.model.OrderDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * OrderDirection Tests - 1:1 copy of original ProcessM OrderDirectionTests.kt
 *
 * Source: https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/querylanguage/OrderDirectionTests.kt
 */
class OrderDirectionTests {
    @Test
    fun parseTest() {
        val directions = arrayOf(OrderDirection.Ascending, OrderDirection.Descending)
        for (direction in directions) {
            assertEquals(direction, OrderDirection.parse(direction.toString()))
            assertEquals(direction, OrderDirection.parse(direction.name))
        }
    }

    @Test
    fun invalidParseTest() {
        assertFailsWith<IllegalArgumentException> { OrderDirection.parse("XYZ") }
    }
}
