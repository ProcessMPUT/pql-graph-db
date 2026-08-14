package com.processm.processminterpreter.neo4j.repository

import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesCustomAttributeCodec
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.xes.model.Log
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class Neo4jLogNodeWriteTest {
    @Test
    fun `parameters preserve custom attributes colliding with log properties`() {
        val now = LocalDateTime.parse("2026-08-14T12:00:00")
        val log = Log(
            id = "log-1",
            name = "Display name",
            createdAt = now,
            updatedAt = now,
            lifecycleModel = "standard lifecycle",
            customAttributes = mapOf(
                "logId" to "custom log id",
                "name" to "custom name",
                StandardAttributeCatalog.LIFECYCLE_MODEL to "custom lifecycle",
                "field.with.dots" to "dotted",
            ),
        )

        val parameters = Neo4jLogNodeWrite.parameters(log)

        assertEquals("log-1", parameters["logId"])
        assertEquals("Display name", parameters["name"])
        val attributes = assertIs<Map<*, *>>(parameters["attributes"])
        assertFalse("logId" in attributes)
        assertFalse("name" in attributes)
        assertEquals("standard lifecycle", attributes[StandardAttributeCatalog.LIFECYCLE_MODEL])
        assertEquals(
            "custom log id",
            attributes[Neo4jXesCustomAttributeCodec.physicalName(Scope.LOG, "logId")],
        )
        assertEquals(
            "custom name",
            attributes[Neo4jXesCustomAttributeCodec.physicalName(Scope.LOG, "name")],
        )
        assertEquals(
            "custom lifecycle",
            attributes[
                Neo4jXesCustomAttributeCodec.physicalName(Scope.LOG, StandardAttributeCatalog.LIFECYCLE_MODEL)
            ],
        )
        assertEquals(
            "dotted",
            attributes[Neo4jXesCustomAttributeCodec.physicalName(Scope.LOG, "field.with.dots")],
        )
    }
}
