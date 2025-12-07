package com.processm.processminterpreter.pql.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for Attribute class.
 *
 * Adapted from ProcessM AttributeTests.kt:
 * https://github.com/ProcessMPUT/processm/blob/master/processm.core/src/test/kotlin/processm/core/querylanguage/AttributeTests.kt
 */
class AttributeTests {

    /**
     * Test parsing of Unicode characters in custom attribute names.
     */
    @Test
    fun unicodeCustomAttributeTest() {
        // Armenian text: Հայերեն
        val attr = Attribute("e:Հայերեն")

        assertEquals("Հայերեն", attr.name, "Attribute name should preserve Unicode")
        assertEquals(Scope.EVENT, attr.scope, "Should have EVENT scope")
        assertEquals("", attr.hoistingPrefix, "Should have no hoisting prefix")
        assertFalse(attr.isStandard, "Unicode attribute should not be standard")
        assertFalse(attr.isClassifier, "Should not be a classifier")
        assertTrue(attr.isTerminal, "Attribute should be terminal (no children)")
    }

    /**
     * Test parsing of special characters in custom attribute names.
     * Also tests hoisting prefix extraction.
     */
    @Test
    fun specialCharactersCustomAttributeTest() {
        // Note: Colon-separated names are supported (org:group)
        // But some special chars like !@#$%^&*() are not valid in attribute names
        // Testing with valid multi-part names
        val attr1 = Attribute("^e:org:group")

        assertEquals("org:group", attr1.name, "Should preserve multi-part name")
        assertEquals("^", attr1.hoistingPrefix, "Should extract hoisting prefix")
        assertEquals(Scope.TRACE, attr1.effectiveScope, "^e should hoist to TRACE")
        assertTrue(attr1.isStandard, "org:group is a standard attribute")

        // Test double hoisting
        val attr2 = Attribute("^^e:timestamp")
        assertEquals("^^", attr2.hoistingPrefix)
        assertEquals(Scope.LOG, attr2.effectiveScope, "^^e should hoist to LOG")
        assertTrue(attr2.isStandard, "timestamp is a standard attribute")
    }

    /**
     * Test standard attribute detection and mapping.
     */
    @Test
    fun standardAttributeTest() {
        // Standard attributes
        val name = Attribute("e:name")
        assertTrue(name.isStandard, "name should be standard")
        assertEquals("concept:name", name.standardName, "Should map to concept:name")
        assertEquals(Type.STRING, name.type, "concept:name is STRING type")

        val timestamp = Attribute("e:timestamp")
        assertTrue(timestamp.isStandard, "timestamp should be standard")
        assertEquals("time:timestamp", timestamp.standardName, "Should map to time:timestamp")
        assertEquals(Type.DATETIME, timestamp.type, "time:timestamp is DATETIME type")

        val resource = Attribute("e:resource")
        assertTrue(resource.isStandard)
        assertEquals("org:resource", resource.standardName)

        // Custom (non-standard) attribute
        val custom = Attribute("e:customAttr")
        assertFalse(custom.isStandard, "customAttr should not be standard")
        assertEquals("", custom.standardName, "Non-standard should have empty standardName")
        assertEquals(Type.UNKNOWN, custom.type, "Custom attributes have UNKNOWN type")
    }

    /**
     * Test scope hoisting.
     */
    @Test
    fun hoistingTest() {
        // No hoisting
        val e = Attribute("e:name")
        assertEquals(Scope.EVENT, e.scope)
        assertEquals("", e.hoistingPrefix)

        // Single hoist: EVENT → TRACE
        val eHoist1 = Attribute("^e:name")
        assertEquals(Scope.TRACE, eHoist1.effectiveScope)
        assertEquals("^", eHoist1.hoistingPrefix)

        // Double hoist: EVENT → LOG
        val eHoist2 = Attribute("^^e:name")
        assertEquals(Scope.LOG, eHoist2.effectiveScope)
        assertEquals("^^", eHoist2.hoistingPrefix)

        // Single hoist from TRACE: TRACE → LOG
        val tHoist = Attribute("^t:name")
        assertEquals(Scope.LOG, tHoist.effectiveScope)

        // No hoisting from LOG
        val l = Attribute("l:name")
        assertEquals(Scope.LOG, l.scope)
    }

    /**
     * Test that hoisting beyond LOG throws exception.
     */
    @Test
    fun invalidHoistingTest() {
        // ^^^e:name would go beyond LOG
        assertThrows(InvalidScopeHoistingException::class.java) {
            Attribute("^^^e:name")
        }

        // ^l:name - LOG has no parent
        assertThrows(InvalidScopeHoistingException::class.java) {
            Attribute("^l:name")
        }

        // ^^t:name would go beyond LOG
        assertThrows(InvalidScopeHoistingException::class.java) {
            Attribute("^^t:name")
        }
    }

    /**
     * Test classifier detection.
     */
    @Test
    fun classifierTest() {
        val c1 = Attribute("e:c:businesscase")
        assertTrue(c1.isClassifier, "c:businesscase should be classifier")

        val c2 = Attribute("e:classifier:activity_resource")
        assertTrue(c2.isClassifier, "classifier:activity_resource should be classifier")

        val notClassifier = Attribute("e:name")
        assertFalse(notClassifier.isClassifier, "name should not be classifier")
    }

    /**
     * Test Neo4j property mapping.
     */
    @Test
    fun neo4jPropertyMappingTest() {
        // Standard attributes have special mappings
        assertEquals("activity", Attribute("e:name").toNeo4jProperty())
        assertEquals("caseId", Attribute("t:name").toNeo4jProperty())
        assertEquals("name", Attribute("l:name").toNeo4jProperty())

        assertEquals("timestamp", Attribute("e:timestamp").toNeo4jProperty())
        assertEquals("resource", Attribute("e:resource").toNeo4jProperty())
        assertEquals("org_group", Attribute("e:group").toNeo4jProperty())

        // Custom attributes: colons → underscores
        assertEquals("my_custom_attr", Attribute("e:my:custom:attr").toNeo4jProperty())
        assertEquals("simple", Attribute("e:simple").toNeo4jProperty())

        // With hoisting
        assertEquals("caseId", Attribute("^e:name").toNeo4jProperty())
        assertEquals("name", Attribute("^^e:name").toNeo4jProperty())
    }

    /**
     * Test default scope (EVENT when no scope specified).
     */
    @Test
    fun defaultScopeTest() {
        // When no scope prefix is specified, default to EVENT
        val attr = Attribute("customAttribute")
        assertEquals(Scope.EVENT, attr.scope, "Should default to EVENT scope")
        assertEquals("customAttribute", attr.name)
        assertEquals("", attr.hoistingPrefix)
    }

    /**
     * Test toString() representation.
     */
    @Test
    fun toStringTest() {
        assertEquals("e:name", Attribute("e:name").toString())
        assertEquals("^e:name", Attribute("^e:name").toString())
        assertEquals("^^e:timestamp", Attribute("^^e:timestamp").toString())
        assertEquals("t:name", Attribute("t:name").toString())
        assertEquals("l:name", Attribute("l:name").toString())
    }

    /**
     * Test invalid attribute syntax.
     */
    @Test
    fun invalidSyntaxTest() {
        // Empty string
        assertThrows(PQLSyntaxException::class.java) {
            Attribute("")
        }

        // Invalid characters (spaces not allowed)
        assertThrows(PQLSyntaxException::class.java) {
            Attribute("e:my attribute")
        }
    }
}
