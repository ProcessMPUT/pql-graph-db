package com.processm.processminterpreter.neo4j.repository

import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesCustomAttributeCodec
import com.processm.processminterpreter.pql.catalog.Scope
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.neo4j.driver.Values
import org.neo4j.driver.types.Node
import java.time.LocalDateTime
import kotlin.test.assertEquals

class Neo4jLogNodeMapperTest {
    @Test
    fun `toDomain decodes custom log keys without exposing storage metadata`() {
        val createdAt = LocalDateTime.parse("2026-08-14T12:00:00")
        val updatedAt = createdAt.plusMinutes(1)
        val encodedName = Neo4jXesCustomAttributeCodec.physicalName(Scope.LOG, "name")
        val encodedCreatedAt = Neo4jXesCustomAttributeCodec.physicalName(Scope.LOG, "createdAt")
        val values = mapOf(
            "logId" to Values.value("log-1"),
            "name" to Values.value("Display name"),
            "createdAt" to Values.value(createdAt),
            "updatedAt" to Values.value(updatedAt),
            encodedName to Values.value("custom name"),
            encodedCreatedAt to Values.value("custom created at"),
        )
        val node = Mockito.mock(Node::class.java)
        `when`(node.keys()).thenReturn(values.keys)
        values.forEach { (key, value) ->
            `when`(node.containsKey(key)).thenReturn(true)
            `when`(node[key]).thenReturn(value)
        }

        val log = Neo4jLogNodeMapper.toDomain(node)

        assertEquals("log-1", log.id)
        assertEquals("Display name", log.name)
        assertEquals(createdAt, log.createdAt)
        assertEquals(updatedAt, log.updatedAt)
        assertEquals(
            mapOf("name" to "custom name", "createdAt" to "custom created at"),
            log.customAttributes,
        )
    }
}
