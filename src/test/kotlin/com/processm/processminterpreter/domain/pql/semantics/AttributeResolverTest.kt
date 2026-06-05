package com.processm.processminterpreter.domain.pql.semantics

import com.processm.processminterpreter.domain.log.Classifier
import com.processm.processminterpreter.domain.pql.syntax.RawAttributeRef
import com.processm.processminterpreter.domain.pql.catalog.AttributeKind
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.catalog.Type
import com.processm.processminterpreter.domain.pql.error.InvalidScopeHoistingException
import com.processm.processminterpreter.domain.pql.error.PQLSyntaxException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AttributeResolverTest {
    private val loc = SourceLocation(1, 0)
    private val resolver = AttributeResolver()

    private fun ref(
        name: String,
        scopeHint: String? = null,
        hoisting: Int = 0,
        wasBracketed: Boolean = false,
    ) = RawAttributeRef(
        rawText = name, hoisting = hoisting, scopeHint = scopeHint,
        name = name, wasBracketed = wasBracketed, location = loc,
    )

    @Test
    fun `resolves standard event name to concept colon name`() {
        val r = resolver.resolve(ref("name"), defaultScope = Scope.EVENT)
        assertEquals(Scope.EVENT, r.effectiveScope)
        assertEquals("concept:name", r.xesStandardName)
        assertEquals(AttributeKind.STANDARD, r.kind)
        assertEquals(Type.STRING, r.type)
    }

    @Test
    fun `explicit scope hint overrides default`() {
        val r = resolver.resolve(ref("name", scopeHint = "t"), defaultScope = Scope.EVENT)
        assertEquals(Scope.TRACE, r.baseScope)
        assertEquals(Scope.TRACE, r.effectiveScope)
        assertEquals("concept:name", r.xesStandardName)
    }

    @Test
    fun `hoisted event name resolves to trace-scoped concept colon name`() {
        val r = resolver.resolve(
            ref("name", scopeHint = "e", hoisting = 1),
            defaultScope = Scope.TRACE,
        )
        assertEquals(Scope.EVENT, r.baseScope)
        assertEquals(Scope.TRACE, r.effectiveScope)
        assertEquals("concept:name", r.xesStandardName)
        assertEquals(AttributeKind.STANDARD, r.kind)
    }

    @Test
    fun `hoisted event timestamp keeps event lookup and trace effective scope`() {
        val r = resolver.resolve(
            ref("timestamp", scopeHint = "e", hoisting = 1),
            defaultScope = Scope.TRACE,
        )
        assertEquals(Scope.EVENT, r.baseScope)
        assertEquals(Scope.TRACE, r.effectiveScope)
        assertEquals("time:timestamp", r.xesStandardName)
        assertEquals(AttributeKind.STANDARD, r.kind)
        assertEquals(Type.DATETIME, r.type)
    }

    @Test
    fun `hoisting beyond LOG throws`() {
        assertThrows<InvalidScopeHoistingException> {
            resolver.resolve(ref("name", scopeHint = "l", hoisting = 1), defaultScope = Scope.LOG)
        }
    }

    @Test
    fun `bracketed non-standard attribute is accepted as custom`() {
        val r = resolver.resolve(
            ref("customFoo", wasBracketed = true),
            defaultScope = Scope.EVENT,
        )
        assertEquals(AttributeKind.CUSTOM, r.kind)
        assertEquals("customFoo", r.name)
        assertNull(r.xesStandardName)
    }

    @Test
    fun `unbracketed non-standard attribute throws NoSuchAttribute`() {
        assertThrows<PQLSyntaxException> {
            resolver.resolve(ref("notAStandardName"), defaultScope = Scope.EVENT)
        }
    }

    @Test
    fun `logId resolves as system attribute at log scope`() {
        val r = resolver.resolve(ref("logId", scopeHint = "l"), defaultScope = Scope.EVENT)

        assertEquals(AttributeKind.SYSTEM, r.kind)
        assertEquals(Scope.LOG, r.baseScope)
        assertEquals(Scope.LOG, r.effectiveScope)
        assertEquals("logId", r.name)
        assertNull(r.xesStandardName)
        assertEquals(Type.ID, r.type)
    }

    @Test
    fun `classifier reference preserves CLASSIFIER kind`() {
        val r = resolver.resolve(
            ref("c:Activity"),
            defaultScope = Scope.EVENT,
            context = ResolutionContext(
                classifiers = listOf(Classifier("Activity", listOf("concept:name", "lifecycle:transition"))),
            ),
        )
        assertEquals(AttributeKind.CLASSIFIER, r.kind)
        assertEquals("c:Activity", r.name)
        assertEquals("Activity", r.classifierName)
        assertEquals(listOf("concept:name", "lifecycle:transition"), r.classifierKeys)
    }

    @Test
    fun `classifier prefix with full classifier keyword`() {
        val r = resolver.resolve(
            ref("classifier:Resource"),
            defaultScope = Scope.EVENT,
            context = ResolutionContext(
                classifiers = listOf(Classifier("Resource", listOf("org:resource", "lifecycle:transition"))),
            ),
        )
        assertEquals(AttributeKind.CLASSIFIER, r.kind)
        assertEquals("Resource", r.classifierName)
    }
}
