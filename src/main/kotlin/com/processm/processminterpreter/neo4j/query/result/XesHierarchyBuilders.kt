package com.processm.processminterpreter.neo4j.query.result

import com.processm.processminterpreter.xes.model.AttributeScope
import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import com.processm.processminterpreter.pql.catalog.Scope
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.pql.cypher.ColumnAlias
import com.processm.processminterpreter.neo4j.xes.schema.LogMetadataProperty
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesSchema
import java.time.Instant
import java.util.UUID

internal val SYNTHETIC_KEY: Map<String, Any?> = emptyMap()

internal class LogBuilder {
    var conceptName: String? = null
    var identityId: UUID? = null
    var lifecycleModel: String? = null
    val customAttributes: MutableMap<String, Any?> = linkedMapOf()
    var classifiersJson: String? = null
    var traceGlobalsJson: String? = null
    var eventGlobalsJson: String? = null
    var extensionsJson: String? = null
    val traces: MutableMap<Map<String, Any?>, TraceBuilder> = linkedMapOf()

    fun absorb(row: Map<String, Any?>, aliases: Map<String, ColumnAlias>) {
        for (attribute in row.projectedAttributes(aliases)) {
            when (attribute.xesName) {
                StandardAttributeCatalog.CONCEPT_NAME -> if (attribute.value != null) conceptName = attribute.value.toString()
                StandardAttributeCatalog.IDENTITY_ID -> if (attribute.value != null) identityId = asUuid(attribute.value)
                else -> customAttributes.putProjected(attribute)
            }
        }
    }

    fun absorbNode(props: Map<String, Any?>) {
        absorbMetadataNode(props)
        for (attribute in props.nodeAttributes(Scope.LOG)) {
            if (attribute.physicalName == StandardAttributeCatalog.LIFECYCLE_MODEL) {
                lifecycleModel = attribute.value.toString()
                continue
            }
            when (attribute.xesName) {
                StandardAttributeCatalog.CONCEPT_NAME -> conceptName = attribute.value.toString()
                StandardAttributeCatalog.IDENTITY_ID -> identityId = asUuid(attribute.value)
                else -> customAttributes.putNode(attribute)
            }
        }
    }

    fun absorbMetadataNode(props: Map<String, Any?>) {
        props.forEach { (physicalName, value) ->
            when (Neo4jXesSchema.logMetadataProperty(physicalName)) {
                LogMetadataProperty.CLASSIFIERS -> classifiersJson = value as? String
                LogMetadataProperty.TRACE_GLOBALS -> traceGlobalsJson = value as? String
                LogMetadataProperty.EVENT_GLOBALS -> eventGlobalsJson = value as? String
                LogMetadataProperty.EXTENSIONS -> extensionsJson = value as? String
                null -> Unit
            }
        }
    }

    fun build(): XesLog = XesLog(
        conceptName = conceptName,
        identityId = identityId,
        lifecycleModel = lifecycleModel,
        classifiers = parseClassifiers(classifiersJson),
        extensions = parseExtensions(extensionsJson),
        traceGlobals = parseGlobals(traceGlobalsJson, AttributeScope.TRACE),
        eventGlobals = parseGlobals(eventGlobalsJson, AttributeScope.EVENT),
        customAttributes = customAttributes.toMap(),
        traces = traces.values.map { it.build() },
    )
}

internal class TraceBuilder {
    var conceptName: String? = null
    var identityId: UUID? = null
    var costCurrency: String? = null
    var costTotal: Double? = null
    var count: Int = 1
    var nullEventCount: Int = 0
    val customAttributes: MutableMap<String, Any?> = linkedMapOf()
    val events: MutableList<EventBuilder> = mutableListOf()

    fun absorb(row: Map<String, Any?>, aliases: Map<String, ColumnAlias>) {
        for (attribute in row.projectedAttributes(aliases)) {
            when (attribute.xesName) {
                StandardAttributeCatalog.CONCEPT_NAME -> if (attribute.value != null) conceptName = attribute.value.toString()
                StandardAttributeCatalog.IDENTITY_ID -> if (attribute.value != null) identityId = asUuid(attribute.value)
                StandardAttributeCatalog.COST_CURRENCY -> if (attribute.value != null) costCurrency = attribute.value.toString()
                StandardAttributeCatalog.COST_TOTAL -> if (attribute.value != null) costTotal = asDouble(attribute.value)
                else -> customAttributes.putProjected(attribute)
            }
        }
    }

    fun absorbNode(props: Map<String, Any?>) {
        for (attribute in props.nodeAttributes(Scope.TRACE)) {
            when (attribute.xesName) {
                StandardAttributeCatalog.CONCEPT_NAME -> conceptName = attribute.value.toString()
                StandardAttributeCatalog.IDENTITY_ID -> identityId = asUuid(attribute.value)
                StandardAttributeCatalog.COST_CURRENCY -> costCurrency = attribute.value.toString()
                StandardAttributeCatalog.COST_TOTAL -> costTotal = asDouble(attribute.value)
                else -> customAttributes.putNode(attribute)
            }
        }
    }

    fun build(): XesTrace = XesTrace(
        conceptName = conceptName,
        identityId = identityId,
        costCurrency = costCurrency,
        costTotal = costTotal,
        count = count,
        customAttributes = customAttributes.toMap(),
        events = events.map { it.build() },
        nullEventCount = nullEventCount,
    )
}

internal class EventBuilder {
    var conceptName: String? = null
    var conceptInstance: String? = null
    var identityId: UUID? = null
    var timeTimestamp: Instant? = null
    var lifecycleTransition: String? = null
    var lifecycleState: String? = null
    var orgResource: String? = null
    var orgRole: String? = null
    var orgGroup: String? = null
    var costCurrency: String? = null
    var costTotal: Double? = null
    val customAttributes: MutableMap<String, Any?> = linkedMapOf()

    fun absorb(row: Map<String, Any?>, aliases: Map<String, ColumnAlias>) {
        if (aliases.isEmpty()) {
            for ((col, value) in row) {
                if (value != null) customAttributes[col] = normalize(value)
            }
            return
        }
        for (attribute in row.projectedAttributes(aliases)) {
            when (attribute.xesName) {
                StandardAttributeCatalog.CONCEPT_NAME -> if (attribute.value != null) conceptName = attribute.value.toString()
                StandardAttributeCatalog.CONCEPT_INSTANCE -> if (attribute.value != null) conceptInstance = attribute.value.toString()
                StandardAttributeCatalog.IDENTITY_ID -> if (attribute.value != null) identityId = asUuid(attribute.value)
                StandardAttributeCatalog.TIME_TIMESTAMP -> if (attribute.value != null) timeTimestamp = asInstant(attribute.value)
                StandardAttributeCatalog.LIFECYCLE_TRANSITION -> if (attribute.value != null) lifecycleTransition = attribute.value.toString()
                StandardAttributeCatalog.LIFECYCLE_STATE -> if (attribute.value != null) lifecycleState = attribute.value.toString()
                StandardAttributeCatalog.ORG_RESOURCE -> if (attribute.value != null) orgResource = attribute.value.toString()
                StandardAttributeCatalog.ORG_ROLE -> if (attribute.value != null) orgRole = attribute.value.toString()
                StandardAttributeCatalog.ORG_GROUP -> if (attribute.value != null) orgGroup = attribute.value.toString()
                StandardAttributeCatalog.COST_CURRENCY -> if (attribute.value != null) costCurrency = attribute.value.toString()
                StandardAttributeCatalog.COST_TOTAL -> if (attribute.value != null) costTotal = asDouble(attribute.value)
                else -> customAttributes.putProjected(attribute)
            }
        }
    }

    fun absorbNode(props: Map<String, Any?>) {
        for (attribute in props.nodeAttributes(Scope.EVENT)) {
            when (attribute.xesName) {
                StandardAttributeCatalog.CONCEPT_NAME -> conceptName = attribute.value.toString()
                StandardAttributeCatalog.CONCEPT_INSTANCE -> conceptInstance = attribute.value.toString()
                StandardAttributeCatalog.IDENTITY_ID -> identityId = asUuid(attribute.value)
                StandardAttributeCatalog.TIME_TIMESTAMP -> timeTimestamp = asInstant(attribute.value)
                StandardAttributeCatalog.LIFECYCLE_TRANSITION -> lifecycleTransition = attribute.value.toString()
                StandardAttributeCatalog.LIFECYCLE_STATE -> lifecycleState = attribute.value.toString()
                StandardAttributeCatalog.ORG_RESOURCE -> orgResource = attribute.value.toString()
                StandardAttributeCatalog.ORG_ROLE -> orgRole = attribute.value.toString()
                StandardAttributeCatalog.ORG_GROUP -> orgGroup = attribute.value.toString()
                StandardAttributeCatalog.COST_CURRENCY -> costCurrency = attribute.value.toString()
                StandardAttributeCatalog.COST_TOTAL -> costTotal = asDouble(attribute.value)
                else -> customAttributes.putNode(attribute)
            }
        }
    }

    fun build(): XesEvent = XesEvent(
        conceptName = conceptName,
        conceptInstance = conceptInstance,
        identityId = identityId,
        timeTimestamp = timeTimestamp,
        lifecycleTransition = lifecycleTransition,
        lifecycleState = lifecycleState,
        orgResource = orgResource,
        orgRole = orgRole,
        orgGroup = orgGroup,
        costCurrency = costCurrency,
        costTotal = costTotal,
        customAttributes = customAttributes.toMap(),
    )
}

private fun MutableMap<String, Any?>.putProjected(attribute: ProjectedAttributeValue) {
    this[attribute.customKey] = normalize(attribute.value)
}

private fun MutableMap<String, Any?>.putNode(attribute: NodeAttributeValue) {
    this[attribute.customKey ?: attribute.xesName ?: attribute.physicalName] = normalize(attribute.value)
}
