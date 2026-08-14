package com.processm.processminterpreter.neo4j.xes.schema

import com.processm.processminterpreter.neo4j.property.NestedAttributePathCodec
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog
import java.util.Base64

/**
 * Reversible storage naming for custom XES attributes that cannot safely use
 * their source name as a Neo4j property key.
 *
 * Most custom attributes keep their existing physical name. Encoding is used
 * only when a name would collide with a standard or structural property, be
 * hidden as storage metadata, or be rewritten by the import sanitizer.
 */
object Neo4jXesCustomAttributeCodec {
    private const val PREFIX = "__xes_custom_v1_"

    fun physicalName(scope: Scope, xesName: String): String =
        if (requiresEncoding(scope, xesName)) encode(xesName) else xesName

    /** Maps both a plain custom attribute and the parent of a nested path. */
    fun expressionPhysicalName(scope: Scope, xesName: String): String =
        NestedAttributePathCodec.mapParent(xesName) { physicalName(scope, it) }
            ?: physicalName(scope, xesName)

    /** Returns the original XES key only for names produced by this codec. */
    fun xesName(scope: Scope, physicalName: String): String? {
        if (!physicalName.startsWith(PREFIX)) return null
        val payload = physicalName.removePrefix(PREFIX)
        if (payload.isEmpty()) return null

        val decoded = runCatching {
            String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)
        }.getOrNull() ?: return null

        return decoded.takeIf { physicalName(scope, it) == physicalName }
    }

    private fun requiresEncoding(scope: Scope, xesName: String): Boolean =
        xesName.startsWith(PREFIX) ||
            xesName.contains('.') ||
            NestedAttributePathCodec.isEncoded(xesName) ||
            Neo4jXesSchema.inversePhysicalName(scope, xesName) != null ||
            xesName in Neo4jXesSchema.writerManagedProperties(scope) ||
            Neo4jXesSchema.isStorageMetadata(scope, xesName) ||
            scope == Scope.LOG && xesName == StandardAttributeCatalog.LIFECYCLE_MODEL

    private fun encode(xesName: String): String =
        PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(xesName.toByteArray(Charsets.UTF_8))
}
