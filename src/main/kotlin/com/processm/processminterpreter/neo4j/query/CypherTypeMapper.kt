package com.processm.processminterpreter.neo4j.query

import org.neo4j.driver.Value
import org.neo4j.driver.types.TypeSystem
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Converts Neo4j driver [Value] objects into Kotlin domain types.
 *
 * Temporal handling deliberately matches MEMORY.md §"Timezone Handling (2026-02-15)":
 *  - `LocalDateTime` values from Neo4j are treated as UTC wall-clock and surfaced as
 *    `ZonedDateTime` anchored at `ZoneOffset.UTC`. They must NOT be promoted with
 *    `ZoneId.systemDefault()` — XES ingestion already normalized offsets to UTC,
 *    so adding the local offset would double-adjust the instant.
 *  - `DATE_TIME` values already carry a zone and are re-zoned to UTC (same-instant)
 *    for a single canonical representation downstream.
 *
 * Any driver Value whose concrete type isn't enumerated falls back to `asObject()`
 * — safe default for maps/lists/nodes/relationships that the higher layers already
 * know how to handle.
 */
@Component
class CypherTypeMapper {
    private val ts = TypeSystem.getDefault()

    fun toKotlin(value: Value): Any? = when {
        value.isNull -> null
        value.hasType(ts.INTEGER()) -> value.asLong()
        value.hasType(ts.FLOAT()) -> value.asDouble()
        value.hasType(ts.BOOLEAN()) -> value.asBoolean()
        value.hasType(ts.STRING()) -> value.asString()
        value.hasType(ts.LOCAL_DATE_TIME()) ->
            ZonedDateTime.of(value.asLocalDateTime(), ZoneOffset.UTC)
        value.hasType(ts.DATE_TIME()) ->
            value.asZonedDateTime().withZoneSameInstant(ZoneOffset.UTC)
        value.hasType(ts.DURATION()) -> value.asIsoDuration()
        value.hasType(ts.LIST()) -> value.asList { toKotlin(it) }
        value.hasType(ts.MAP()) -> value.asMap { toKotlin(it) }
        else -> value.asObject()
    }
}
