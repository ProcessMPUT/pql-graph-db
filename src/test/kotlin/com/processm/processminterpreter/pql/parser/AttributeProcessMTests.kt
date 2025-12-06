package com.processm.processminterpreter.pql.parser

import com.processm.processminterpreter.pql.model.Attribute
import com.processm.processminterpreter.pql.model.Scope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AttributeProcessMTests {

    @Test
    fun unicodeCustomAttributeTest() {
        val attribute = Attribute(
            "[Ոչ ոք չի սիրում ցավը հենց այդպիսին, ոչ ոք չի փնտրում այն և չի տենչում հենց նրա համար, որ դա ցավ է..]",
            0,
            0
        )
        assertEquals("", attribute.hoistingPrefix)
        assertEquals(Scope.EVENT, attribute.scope)
        assertEquals(Scope.EVENT, attribute.effectiveScope)
        assertEquals(
            "Ոչ ոք չի սիրում ցավը հենց այդպիսին, ոչ ոք չի փնտրում այն և չի տենչում հենց նրա համար, որ դա ցավ է..",
            attribute.name
        )
        assertEquals("", attribute.standardName)
        assertFalse(attribute.isStandard)
        assertFalse(attribute.isClassifier)
        assertTrue(attribute.isTerminal)
    }

    @Test
    fun specialCharactersCustomAttributeTest() {
        val attribute = Attribute(
            "[^trace:!@#\\$%^&*():-=]",
            0,
            0
        )
        assertEquals("^", attribute.hoistingPrefix)
        assertEquals(Scope.TRACE, attribute.scope)
        assertEquals(Scope.LOG, attribute.effectiveScope)
        assertEquals("!@#\\$%^&*():-=", attribute.name)
        assertEquals("", attribute.standardName)
        assertFalse(attribute.isStandard)
        assertFalse(attribute.isClassifier)
        assertTrue(attribute.isTerminal)
    }
}
