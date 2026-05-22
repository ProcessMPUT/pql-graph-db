package com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.metadata

import com.processm.processminterpreter.domain.log.AttributeScope
import com.processm.processminterpreter.domain.log.Classifier
import com.processm.processminterpreter.domain.log.Extension
import com.processm.processminterpreter.domain.log.GlobalAttribute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Unit tests for log-level XES metadata encoding. Driver-backed
 * round-trip behavior is exercised by the Testcontainers-based integration suite
 * (added in the verification phase).
 */
class XesLogMetadataCodecTest {
    @Test
    fun `serializeClassifiers returns null for empty list`() {
        assertNull(XesLogMetadataCodec.serializeClassifiers(emptyList()))
    }

    @Test
    fun `classifiers round-trip preserves name and keys in order`() {
        val classifiers = listOf(
            Classifier("Activity", listOf("concept:name", "lifecycle:transition")),
            Classifier("Resource", listOf("org:resource")),
        )
        val encoded = XesLogMetadataCodec.serializeClassifiers(classifiers)!!
        val decoded = XesLogMetadataCodec.deserializeClassifiers(encoded)
        assertEquals(classifiers, decoded)
    }

    @Test
    fun `extensions round-trip preserves all three fields`() {
        val extensions = listOf(
            Extension("Concept", "concept", "http://www.xes-standard.org/concept.xesext"),
            Extension("Time", "time", "http://www.xes-standard.org/time.xesext"),
        )
        val encoded = XesLogMetadataCodec.serializeExtensions(extensions)!!
        val decoded = XesLogMetadataCodec.deserializeExtensions(encoded)
        assertEquals(extensions, decoded)
    }

    @Test
    fun `serializeExtensions returns null for empty list`() {
        assertNull(XesLogMetadataCodec.serializeExtensions(emptyList()))
    }

    @Test
    fun `globals round-trip preserves keys and values in the requested scope`() {
        // String values round-trip losslessly. Numeric widening (Long -> Int on
        // deserialize) is accepted downstream; this test only pins the keys and
        // the scope assignment, not numeric type fidelity.
        val globals = listOf(
            GlobalAttribute(AttributeScope.TRACE, "concept:name", "DEFAULT"),
            GlobalAttribute(AttributeScope.TRACE, "org:resource", "system"),
        )
        val encoded = XesLogMetadataCodec.serializeGlobals(globals)!!
        val decoded = XesLogMetadataCodec.deserializeGlobals(encoded, AttributeScope.TRACE)
        assertEquals(
            globals.map { it.key to it.value },
            decoded.map { it.key to it.value },
        )
        // Scope is applied by deserializeGlobals, not stored in JSON.
        decoded.forEach { assertEquals(AttributeScope.TRACE, it.scope) }
    }

    @Test
    fun `deserializeGlobals applies scope from caller, not from JSON`() {
        val traceEncoded = XesLogMetadataCodec.serializeGlobals(
            listOf(GlobalAttribute(AttributeScope.TRACE, "k", "v")),
        )!!
        val asEvent = XesLogMetadataCodec.deserializeGlobals(traceEncoded, AttributeScope.EVENT)
        assertEquals(AttributeScope.EVENT, asEvent.single().scope)
    }

    @Test
    fun `empty-string JSON deserializes to empty lists safely`() {
        assertEquals(emptyList<Classifier>(), XesLogMetadataCodec.deserializeClassifiers(""))
        assertEquals(emptyList<Extension>(), XesLogMetadataCodec.deserializeExtensions(""))
        assertEquals(
            emptyList<GlobalAttribute>(),
            XesLogMetadataCodec.deserializeGlobals("", AttributeScope.TRACE),
        )
    }
}
