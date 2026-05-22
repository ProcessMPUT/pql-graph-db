package com.processm.processminterpreter.application.processm

private val XES_ATTRIBUTE_TYPES = setOf("string", "date", "float", "int", "boolean", "id")

internal data class XesJsonAttribute(
    val key: String,
    val value: String,
)

internal data class XesJsonNode(
    val attributes: List<XesJsonAttribute>,
    val traces: List<XesJsonNode> = emptyList(),
    val events: List<XesJsonNode> = emptyList(),
    val rawEventCount: Int = events.size,
) {
    fun attributesByKey(): Map<String, String> =
        attributes.associate { attribute -> attribute.key to attribute.value }

    fun attributeCounts(): Map<String, Int> =
        attributes.groupingBy { attribute -> attribute.key }.eachCount()
}

internal object XesJsonTreeReader {
    fun readLog(json: List<Map<String, Any?>>): XesJsonNode? {
        if (json.isEmpty()) {
            return null
        }

        val first = json.first()
        val rawLog = first["log"].asObjectMap() ?: first
        return readNode(rawLog)
    }

    private fun readNode(raw: Map<String, Any?>): XesJsonNode {
        val traces = readChildren(raw, "trace")
        val events = readChildren(raw, "event")
        return XesJsonNode(
            attributes = readAttributes(raw),
            traces = traces.nodes,
            events = events.nodes,
            rawEventCount = events.rawCount,
        )
    }

    private fun readChildren(
        parent: Map<String, Any?>,
        childKey: String,
    ): ChildNodes =
        if (parent.containsKey(childKey)) {
            flattenChildren(parent[childKey], childKey)
        } else {
            ChildNodes.empty()
        }

    private fun flattenChildren(
        raw: Any?,
        childKey: String,
    ): ChildNodes =
        when (raw) {
            null -> ChildNodes(nodes = emptyList(), rawCount = 1)
            is List<*> -> raw.fold(ChildNodes.empty()) { acc, item -> acc + flattenChildren(item, childKey) }
            is Map<*, *> -> {
                val nodeMap = raw.stringKeyMap()
                val nested = readChildren(nodeMap, childKey)
                ChildNodes(
                    nodes = listOf(readNode(nodeMap)) + nested.nodes,
                    rawCount = 1 + nested.rawCount,
                )
            }
            else -> ChildNodes(nodes = emptyList(), rawCount = 1)
        }

    private fun readAttributes(node: Map<String, Any?>): List<XesJsonAttribute> =
        buildList {
            for (type in XES_ATTRIBUTE_TYPES) {
                val raw = node[type] ?: continue
                for (item in raw.asObjectMaps()) {
                    val key = item["@key"]?.toString() ?: continue
                    val value = item["@value"]?.toString() ?: continue
                    add(XesJsonAttribute(key = key, value = value))
                }
            }
        }
}

private data class ChildNodes(
    val nodes: List<XesJsonNode>,
    val rawCount: Int,
) {
    operator fun plus(other: ChildNodes): ChildNodes =
        ChildNodes(
            nodes = nodes + other.nodes,
            rawCount = rawCount + other.rawCount,
        )

    companion object {
        fun empty(): ChildNodes = ChildNodes(nodes = emptyList(), rawCount = 0)
    }
}

private fun Any?.asObjectMap(): Map<String, Any?>? =
    (this as? Map<*, *>)?.stringKeyMap()

private fun Any?.asObjectMaps(): List<Map<String, Any?>> =
    when (this) {
        is List<*> -> mapNotNull { it.asObjectMap() }
        is Map<*, *> -> listOf(stringKeyMap())
        else -> emptyList()
    }

private fun Map<*, *>.stringKeyMap(): Map<String, Any?> =
    entries.mapNotNull { (key, value) ->
        (key as? String)?.let { it to value }
    }.toMap()
