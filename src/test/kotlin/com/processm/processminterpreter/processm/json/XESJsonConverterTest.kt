package com.processm.processminterpreter.processm.json

import com.processm.processminterpreter.processm.json.QueryJsonProjection
import com.processm.processminterpreter.xes.model.AttributeScope
import com.processm.processminterpreter.xes.model.Classifier
import com.processm.processminterpreter.xes.model.Extension
import com.processm.processminterpreter.xes.model.GlobalAttribute
import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XESJsonConverterTest {
    @Test
    fun `converter preserves classifier scopes`() {
        val log = XesLog(
            classifiers = listOf(
                Classifier("Activity", listOf("concept:name")),
                Classifier("Department", listOf("org:group"), AttributeScope.TRACE),
            ),
        )

        val classifiers = XESJsonConverter.convertToXESJson(listOf(log))["log"]
            .asMap()["classifier"] as List<*>

        assertEquals("event", classifiers[0].asMap()["@scope"])
        assertEquals("trace", classifiers[1].asMap()["@scope"])
    }

    @Test
    fun `converter does not duplicate standard trace and event attributes`() {
        val log =
            XesLog(
                conceptName = "teleclaims.mxml",
                lifecycleModel = "standard",
                customAttributes =
                    mapOf(
                        "dataStoreId" to "source datastore id",
                        "concept:name" to "teleclaims.mxml",
                        "lifecycle:model" to "standard",
                    ),
                traces =
                    listOf(
                        XesTrace(
                            conceptName = "0",
                            customAttributes =
                                mapOf(
                                    "concept:name" to "0",
                                    "description" to "Simulated process instance",
                                ),
                            events =
                                listOf(
                                    XesEvent(
                                        conceptName = "incoming claim",
                                        lifecycleTransition = "complete",
                                        orgResource = "customer",
                                        timeTimestamp = Instant.parse("1970-01-01T00:00:00Z"),
                                        customAttributes =
                                            mapOf(
                                                "concept:name" to "incoming claim",
                                                "lifecycle:transition" to "complete",
                                                "org:resource" to "customer",
                                                "time:timestamp" to "1970-01-01T00:00:00Z",
                                                "call centre" to "Brisbane",
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )

        val json = XESJsonConverter.convertToXESJson(listOf(log))
        val logNode = json["log"].asMap()
        val traceNode = logNode["trace"].asMap()
        val eventNode = traceNode["event"].asMap()

        assertEquals("source datastore id", flatAttributes(logNode)["dataStoreId"])
        assertEquals(1, attributeCount(logNode, "concept:name"))
        assertEquals("teleclaims.mxml", flatAttributes(logNode)["concept:name"])
        assertEquals("standard", flatAttributes(logNode)["lifecycle:model"])
        assertEquals(1, attributeCount(traceNode, "concept:name"))
        assertEquals(1, attributeCount(eventNode, "concept:name"))
        assertEquals(1, attributeCount(eventNode, "lifecycle:transition"))
        assertEquals(1, attributeCount(eventNode, "org:resource"))
        assertEquals(1, attributeCount(eventNode, "time:timestamp"))
        assertEquals("date", typeOfAttribute(eventNode, "time:timestamp"))
    }

    @Test
    fun `projected log name is emitted once when explicitly selected`() {
        val log =
            XesLog(
                conceptName = "teleclaims.mxml",
                lifecycleModel = "standard",
                customAttributes =
                    mapOf(
                        "concept:name" to "teleclaims.mxml",
                        "lifecycle:model" to "standard",
                    ),
            )

        val json = XESJsonConverter.convertToXESJson(listOf(log), isProjectedQuery = true)
        val logNode = json["log"].asMap()

        assertEquals(1, attributeCount(logNode, "concept:name"))
        assertEquals("teleclaims.mxml", flatAttributes(logNode)["concept:name"])
        assertFalse(flatAttributes(logNode).containsKey("lifecycle:model"))
    }

    @Test
    fun `projected log description is preserved as XES attribute`() {
        val log =
            XesLog(
                customAttributes = mapOf("description" to "Simulated process"),
            )

        val json = XESJsonConverter.convertToXESJson(
            logs = listOf(log),
            isProjectedQuery = true,
            projectedLogAttrs = setOf("description"),
        )
        val logNode = json["log"].asMap()

        assertEquals("Simulated process", flatAttributes(logNode)["description"])
    }

    @Test
    fun `implicit hierarchy preserves source log description`() {
        val log =
            XesLog(
                customAttributes = mapOf("description" to "Simulated process"),
            )

        val json = XESJsonConverter.convertToXESJson(listOf(log))
        val logNode = json["log"].asMap()

        assertEquals("Simulated process", flatAttributes(logNode)["description"])
    }

    @Test
    fun `projected log wildcard keeps ProcessM metadata shape`() {
        val log =
            XesLog(
                conceptName = "teleclaims.mxml",
                identityId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                lifecycleModel = "standard",
                traceGlobals = listOf(GlobalAttribute(AttributeScope.TRACE, "concept:name", "__INVALID__")),
                eventGlobals = listOf(GlobalAttribute(AttributeScope.EVENT, "concept:name", "__INVALID__")),
                customAttributes =
                    mapOf(
                        "source" to "CPN Tools simulation",
                        "description" to "Simulated process",
                    ),
            )

        val json = XESJsonConverter.convertToXESJson(
            logs = listOf(log),
            isProjectedQuery = true,
            logSelectAll = true,
        )
        val logNode = json["log"].asMap()

        assertTrue(logNode.containsKey("global"))
        assertEquals("id", typeOfAttribute(logNode, "identity:id"))
        assertEquals("00000000-0000-0000-0000-000000000001", flatAttributes(logNode)["identity:id"])
        assertEquals("standard", flatAttributes(logNode)["lifecycle:model"])
        assertEquals("CPN Tools simulation", flatAttributes(logNode)["source"])
        assertEquals(1, attributeCount(logNode, "concept:name"))
        assertEquals("teleclaims.mxml", flatAttributes(logNode)["concept:name"])
        assertEquals("Simulated process", flatAttributes(logNode)["description"])
    }

    @Test
    fun `formatter preserves source log attributes named like storage fields in multiple logs`() {
        val descriptions = listOf("Log file created in CPN Tools", "Simulated process")
        val logs = descriptions.mapIndexed { index, description ->
            XesLog(
                conceptName = "log-$index",
                identityId = UUID(0, index + 1L),
                lifecycleModel = "standard",
                extensions = listOf(Extension("Concept", "concept", "urn:concept")),
                classifiers = listOf(Classifier("Activity", listOf("concept:name"))),
                traceGlobals = listOf(GlobalAttribute(AttributeScope.TRACE, "concept:name", "DEFAULT")),
                customAttributes = mapOf(
                    "description" to description,
                    "traceGlobals" to index + 10,
                    "eventGlobals" to (index == 0),
                    "extensions" to Instant.parse("2026-09-06T00:00:00Z"),
                    "classifiers" to index + 0.5,
                    "dataStoreId" to UUID(1, index.toLong()),
                    "logId" to "source-log-$index",
                    "concept:name" to "log-$index",
                    "lifecycle:model" to "standard",
                ),
            )
        }
        val customTypes = mapOf("description" to "string", "traceGlobals" to "int", "eventGlobals" to "boolean",
            "extensions" to "date", "classifiers" to "float", "dataStoreId" to "id", "logId" to "string")
        for (explicitWildcard in listOf(false, true)) {
            val documents = ProcessMXesJsonFormatter().formatAsXesJson(QueryJsonProjection(
                logs = logs,
                hasExplicitSelect = explicitWildcard,
                selectAllScopes = if (explicitWildcard) setOf(Scope.LOG) else emptySet(),
            ))
            assertEquals(2, documents.size)
            documents.zip(logs).forEach { (document, source) ->
                val node = document["log"].asMap()
                val values = flatAttributes(node)
                customTypes.forEach { (key, type) ->
                    assertEquals(type, typeOfAttribute(node, key), key)
                    assertEquals(source.customAttributes.getValue(key).toString(), values[key], key)
                    assertEquals(1, attributeCount(node, key), key)
                }
                assertEquals(source.conceptName, values["concept:name"])
                assertEquals("standard", values["lifecycle:model"])
                assertEquals(source.identityId.toString(), values["identity:id"])
                assertEquals("id", typeOfAttribute(node, "identity:id"))
                listOf("concept:name", "lifecycle:model", "identity:id").forEach {
                    assertEquals(1, attributeCount(node, it), it)
                }
                assertEquals(listOf(mapOf("@name" to "Concept", "@prefix" to "concept", "@uri" to "urn:concept")), node["extension"])
                assertEquals(listOf(mapOf("@name" to "Activity", "@scope" to "event", "@keys" to "concept:name")), node["classifier"])
                assertEquals(listOf(mapOf("@scope" to "trace", "string" to mapOf("@key" to "concept:name", "@value" to "DEFAULT"))), node["global"])
            }
        }
    }

    @Test
    fun `formatter honors include trace and event flags`() {
        val formatter = ProcessMXesJsonFormatter()
        val log =
            XesLog(
                conceptName = "teleclaims.mxml",
                traces =
                    listOf(
                        XesTrace(
                            conceptName = "0",
                            events = listOf(XesEvent(conceptName = "incoming claim")),
                        ),
                    ),
            )

        val withoutTraces =
            formatter.formatAsXesJson(
                QueryJsonProjection(
                    logs = listOf(log),
                    includeTraces = false,
                    includeEvents = false,
                ),
            )[0]["log"].asMap()
        val withoutEvents =
            formatter.formatAsXesJson(
                QueryJsonProjection(
                    logs = listOf(log),
                    includeTraces = true,
                    includeEvents = false,
                ),
            )[0]["log"].asMap()

        assertFalse(withoutTraces.containsKey("trace"))
        assertFalse(withoutEvents["trace"].asMap().containsKey("event"))
    }

    @Test
    fun `formatter emits projected trace standard attributes from plan metadata`() {
        val formatter = ProcessMXesJsonFormatter()
        val log = XesLog(traces = listOf(XesTrace(conceptName = "0", costTotal = 47.0)))

        val json =
            formatter.formatAsXesJson(
                QueryJsonProjection(
                    logs = listOf(log),
                    hasExplicitSelect = true,
                    projectedTraceStandardAttributes =
                        setOf(
                            StandardAttributeCatalog.CONCEPT_NAME,
                            StandardAttributeCatalog.COST_TOTAL,
                        ),
                    includeTraces = true,
                    includeEvents = false,
                ),
            )

        val traceNode = json[0]["log"].asMap()["trace"].asMap()
        assertEquals("0", flatAttributes(traceNode)["concept:name"])
        assertEquals("47.0", flatAttributes(traceNode)["cost:total"])
    }

    @Test
    fun `formatter emits full trace attributes for explicit trace wildcard`() {
        val formatter = ProcessMXesJsonFormatter()
        val log =
            XesLog(
                traces =
                    listOf(
                        XesTrace(
                            conceptName = "1",
                            costCurrency = "EUR",
                            costTotal = 47.0,
                        ),
                    ),
            )

        val json =
            formatter.formatAsXesJson(
                QueryJsonProjection(
                    logs = listOf(log),
                    hasExplicitSelect = true,
                    selectAllScopes = setOf(Scope.TRACE),
                    includeTraces = true,
                    includeEvents = false,
                ),
            )

        assertEquals(
            mapOf(
                "concept:name" to "1",
                "cost:currency" to "EUR",
                "cost:total" to "47.0",
            ),
            flatAttributes(json[0]["log"].asMap()["trace"].asMap()),
        )
    }

    @Test
    fun `single unprojected event is serialized as null placeholder`() {
        val log =
            XesLog(
                traces =
                    listOf(
                        XesTrace(
                            events = listOf(XesEvent()),
                        ),
                    ),
            )

        val json = XESJsonConverter.convertToXESJson(listOf(log), isProjectedQuery = true)
        val traceNode = json["log"].asMap()["trace"].asMap()

        assertEquals(null, traceNode["event"])
    }

    @Test
    fun `event compatibility json preserves attributes across separated type runs`() {
        val log =
            XesLog(
                traces =
                    listOf(
                        XesTrace(
                            events =
                                listOf(
                                    XesEvent(
                                        conceptName = "with-both",
                                        lifecycleTransition = "complete",
                                        timeTimestamp = Instant.parse("2026-05-15T00:03:00Z"),
                                        costCurrency = "EUR",
                                        costTotal = 1.0,
                                    ),
                                ),
                        ),
                    ),
            )

        val eventNode =
            XESJsonConverter.convertToXESJson(listOf(log))["log"]
                .asMap()["trace"]
                .asMap()["event"]
                .asMap()

        assertEquals(
            mapOf(
                "concept:name" to "with-both",
                "cost:currency" to "EUR",
                "lifecycle:transition" to "complete",
                "cost:total" to "1.0",
                "time:timestamp" to "2026-05-15T00:03:00Z",
            ),
            flatAttributes(eventNode),
        )
    }

    @Test
    fun `compatibility json preserves every type run at each hierarchy scope`() {
        val log =
            XesLog(
                lifecycleModel = "standard",
                customAttributes = mapOf(
                    "alpha" to "A",
                    "beta" to 1.0,
                    "alpha" to "A",
                "gamma" to "G",
                ),
                traces =
                    listOf(
                        XesTrace(
                            conceptName = "case",
                            costTotal = 2.0,
                            customAttributes = mapOf("zzz" to "tail"),
                            events = listOf(XesEvent(conceptName = "event")),
                        ),
                    ),
            )

        val json = XESJsonConverter.convertToXESJson(listOf(log))
        val logNode = json["log"].asMap()
        val traceNode = logNode["trace"].asMap()

        assertEquals(
            mapOf(
                "alpha" to "A",
                "gamma" to "G",
                "lifecycle:model" to "standard",
                "beta" to "1.0",
            ),
            flatAttributes(logNode) - "identity:id",
        )
        assertEquals(
            mapOf(
                "concept:name" to "case",
                "zzz" to "tail",
                "cost:total" to "2.0",
            ),
            flatAttributes(traceNode),
        )
    }

    @Test
    fun `identity ids keep XES id typing across all hierarchy levels`() {
        val log =
            XesLog(
                identityId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                traces =
                    listOf(
                        XesTrace(
                            identityId = UUID.fromString("00000000-0000-0000-0000-000000000002"),
                            events =
                                listOf(
                                    XesEvent(
                                        identityId = UUID.fromString("00000000-0000-0000-0000-000000000003"),
                                    ),
                                ),
                        ),
                    ),
            )

        val logNode = XESJsonConverter.convertToXESJson(listOf(log))["log"].asMap()
        val traceNode = logNode["trace"].asMap()
        val eventNode = traceNode["event"].asMap()

        assertEquals("id", typeOfAttribute(logNode, "identity:id"))
        assertEquals("id", typeOfAttribute(traceNode, "identity:id"))
        assertEquals("id", typeOfAttribute(eventNode, "identity:id"))
    }

    @Test
    fun `non-projected compatibility json synthesizes ProcessM log identity id`() {
        val log =
            XesLog(
                conceptName = "Hospital_log",
                customAttributes = mapOf("logId" to "source log id"),
            )

        val first = XESJsonConverter.convertToXESJson(listOf(log))["log"].asMap()
        val second = XESJsonConverter.convertToXESJson(listOf(log))["log"].asMap()
        val projected = XESJsonConverter.convertToXESJson(listOf(log), isProjectedQuery = true)["log"].asMap()

        assertEquals("id", typeOfAttribute(first, "identity:id"))
        assertEquals("source log id", flatAttributes(first)["logId"])
        assertEquals(flatAttributes(first)["identity:id"], flatAttributes(second)["identity:id"])
        assertFalse(flatAttributes(projected).containsKey("identity:id"))
    }

    @Test
    fun `formatter does not treat synthetic row keys as explicit projection`() {
        val formatter = ProcessMXesJsonFormatter()
        val log =
            XesLog(
                customAttributes = mapOf("meta_3TU:language" to "eng"),
                traceGlobals = listOf(GlobalAttribute(AttributeScope.TRACE, "concept:name", "DEFAULT")),
                eventGlobals = listOf(GlobalAttribute(AttributeScope.EVENT, "concept:name", "DEFAULT")),
                traces = listOf(XesTrace(events = listOf(XesEvent(conceptName = "A")))),
            )

        val json =
            formatter.formatAsXesJson(
                QueryJsonProjection(
                    logs = listOf(log),
                    hasExplicitSelect = false,
                ),
            )

        val logNode = json[0]["log"].asMap()
        assertEquals("eng", flatAttributes(logNode)["meta_3TU:language"])
        assertTrue(logNode.containsKey("global"), "implicit query must keep log globals")
    }

    private fun attributeCount(
        node: Map<String, Any?>,
        key: String,
    ): Int =
        typedAttributes(node)
            .count { it.second["@key"] == key }

    private fun typeOfAttribute(
        node: Map<String, Any?>,
        key: String,
    ): String? =
        typedAttributes(node)
            .firstOrNull { it.second["@key"] == key }
            ?.first

    private fun flatAttributes(node: Map<String, Any?>): Map<String, String> =
        typedAttributes(node)
            .associate { (_, attr) -> attr["@key"].toString() to attr["@value"].toString() }

    private fun typedAttributes(node: Map<String, Any?>): List<Pair<String, Map<String, Any?>>> =
        listOf("string", "date", "float", "int", "boolean", "id").flatMap { type ->
            when (val raw = node[type]) {
                is List<*> -> raw.filterIsInstance<Map<String, Any?>>().map { type to it }
                is Map<*, *> -> listOf(type to raw.asMap())
                else -> emptyList()
            }
        }

    private fun Any?.asMap(): Map<String, Any?> {
        val map = this as? Map<*, *> ?: error("Expected map, got ${this?.javaClass?.name ?: "null"}")
        return map.entries.associate { (key, value) ->
            require(key is String) { "Expected string key, got ${key?.javaClass?.name ?: "null"}" }
            key to value
        }
    }
}
