package com.processm.processminterpreter.domain.pql.catalog

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SystemAttributeCatalogTest {

    @Test
    fun `logId is exposed only at log scope`() {
        val attribute = SystemAttributeCatalog.lookup(Scope.LOG, "logId")!!

        assertEquals(Scope.LOG, attribute.scope)
        assertEquals("logId", attribute.name)
        assertEquals(Type.ID, attribute.type)
        assertNull(SystemAttributeCatalog.lookup(Scope.TRACE, "logId"))
        assertNull(SystemAttributeCatalog.lookup(Scope.EVENT, "logId"))
    }
}
