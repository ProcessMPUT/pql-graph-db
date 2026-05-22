package com.processm.processminterpreter.domain.pql.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StandardAttributeCatalogTest {

    @Test
    fun `event name shorthand expands to concept colon name`() {
        val a = StandardAttributeCatalog.lookup(Scope.EVENT, "name")!!
        assertEquals("concept:name", a.canonicalName)
        assertEquals(Scope.EVENT, a.scope)
        assertEquals(Type.STRING, a.type)
    }

    @Test
    fun `event timestamp shorthand expands to time colon timestamp with DATETIME type`() {
        val a = StandardAttributeCatalog.lookup(Scope.EVENT, "timestamp")!!
        assertEquals("time:timestamp", a.canonicalName)
        assertEquals(Type.DATETIME, a.type)
    }

    @Test
    fun `canonical XES name is accepted directly`() {
        val a = StandardAttributeCatalog.lookup(Scope.EVENT, "concept:name")!!
        assertEquals("concept:name", a.canonicalName)
    }

    @Test
    fun `trace name shorthand expands to concept colon name at TRACE scope`() {
        val a = StandardAttributeCatalog.lookup(Scope.TRACE, "name")!!
        assertEquals("concept:name", a.canonicalName)
        assertEquals(Scope.TRACE, a.scope)
    }

    @Test
    fun `log version shorthand expands to xes colon version`() {
        val a = StandardAttributeCatalog.lookup(Scope.LOG, "version")!!
        assertEquals("xes:version", a.canonicalName)
    }

    @Test
    fun `unknown shorthand returns null`() {
        assertNull(StandardAttributeCatalog.lookup(Scope.EVENT, "notAnAttribute"))
    }

    @Test
    fun `scope mismatch returns null (timestamp is EVENT-only)`() {
        assertNull(StandardAttributeCatalog.lookup(Scope.LOG, "timestamp"))
        assertNull(StandardAttributeCatalog.lookup(Scope.TRACE, "timestamp"))
    }

    @Test
    fun `cost total has NUMBER type at event and trace scopes`() {
        assertEquals(Type.NUMBER, StandardAttributeCatalog.lookup(Scope.EVENT, "total")!!.type)
        assertEquals(Type.NUMBER, StandardAttributeCatalog.lookup(Scope.TRACE, "total")!!.type)
    }

    @Test
    fun `identity id is accepted as both shorthand and canonical`() {
        val byShort = StandardAttributeCatalog.lookup(Scope.EVENT, "identity:id")!!
        assertEquals("identity:id", byShort.canonicalName)
        assertEquals(Type.ID, byShort.type)
    }

    @Test
    fun `classifier prefix detection`() {
        assertTrue(StandardAttributeCatalog.isClassifier("c:Activity"))
        assertTrue(StandardAttributeCatalog.isClassifier("classifier:Resource"))
        assertEquals(false, StandardAttributeCatalog.isClassifier("name"))
        assertEquals(false, StandardAttributeCatalog.isClassifier("concept:name"))
    }
}
