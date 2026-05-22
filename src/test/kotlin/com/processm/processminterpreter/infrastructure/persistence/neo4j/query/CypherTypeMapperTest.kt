package com.processm.processminterpreter.infrastructure.persistence.neo4j.query

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.neo4j.driver.Values
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

class CypherTypeMapperTest {
    private val mapper = CypherTypeMapper()

    @Test
    fun `NULL value maps to kotlin null`() {
        assertNull(mapper.toKotlin(Values.NULL))
    }

    @Test
    fun `integer value maps to Long`() {
        assertEquals(42L, mapper.toKotlin(Values.value(42)))
    }

    @Test
    fun `float value maps to Double`() {
        assertEquals(3.14, mapper.toKotlin(Values.value(3.14)))
    }

    @Test
    fun `boolean value maps to Boolean`() {
        assertEquals(true, mapper.toKotlin(Values.value(true)))
    }

    @Test
    fun `string value maps to String`() {
        assertEquals("hello", mapper.toKotlin(Values.value("hello")))
    }

    @Test
    fun `LocalDateTime value maps to UTC ZonedDateTime`() {
        val dt = LocalDateTime.of(2023, 1, 2, 3, 4, 5)
        val result = mapper.toKotlin(Values.value(dt)) as ZonedDateTime
        assertEquals(2023, result.year)
        assertEquals(ZoneOffset.UTC, result.zone)
        assertEquals(dt, result.toLocalDateTime())
    }

    @Test
    fun `zoned DATE_TIME value is normalized to UTC same-instant`() {
        val zdt = ZonedDateTime.of(2023, 6, 15, 12, 0, 0, 0, ZoneOffset.ofHours(2))
        val result = mapper.toKotlin(Values.value(zdt)) as ZonedDateTime
        assertEquals(ZoneOffset.UTC, result.zone)
        assertEquals(zdt.toInstant(), result.toInstant())
        assertEquals(10, result.hour)
    }

    @Test
    fun `list value maps element-wise`() {
        val result = mapper.toKotlin(Values.value(listOf(1, 2, 3))) as List<*>
        assertEquals(listOf(1L, 2L, 3L), result)
    }

    @Test
    fun `map value recurses`() {
        val result = mapper.toKotlin(Values.value(mapOf("k" to "v", "n" to 7))) as Map<*, *>
        assertEquals("v", result["k"])
        assertEquals(7L, result["n"])
    }

    @Test
    fun `null-inside-list is preserved as null`() {
        val list = Values.value(listOf<Any?>("a", null, "b"))
        val result = mapper.toKotlin(list) as List<*>
        assertEquals(3, result.size)
        assertEquals("a", result[0])
        assertNull(result[1])
        assertTrue(result[2] == "b")
    }
}
