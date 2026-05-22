package com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.mapping

import com.processm.processminterpreter.domain.log.xes.XesEvent
import com.processm.processminterpreter.domain.log.xes.XesLog
import com.processm.processminterpreter.domain.log.xes.XesTrace
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.catalog.StandardAttributeCatalog
import com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.schema.Neo4jXesSchema
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneOffset

@Component
class Neo4jXesAttributeMapper {
    fun logAttributes(log: XesLog): Map<String, Any?> = buildMap {
        putAll(log.customAttributes)
        log.conceptName?.let { putPhysical(Scope.LOG, StandardAttributeCatalog.CONCEPT_NAME, it) }
        log.identityId?.let { putPhysical(Scope.LOG, StandardAttributeCatalog.IDENTITY_ID, it.toString()) }
        log.lifecycleModel?.let { put(StandardAttributeCatalog.LIFECYCLE_MODEL, it) }
    }

    fun traceAttributes(trace: XesTrace): Map<String, Any?> = buildMap {
        putAll(trace.customAttributes)
        trace.conceptName?.let { putPhysical(Scope.TRACE, StandardAttributeCatalog.CONCEPT_NAME, it) }
        trace.identityId?.let { putPhysical(Scope.TRACE, StandardAttributeCatalog.IDENTITY_ID, it.toString()) }
        trace.costCurrency?.let { putPhysical(Scope.TRACE, StandardAttributeCatalog.COST_CURRENCY, it) }
        trace.costTotal?.let { putPhysical(Scope.TRACE, StandardAttributeCatalog.COST_TOTAL, it) }
    }

    fun eventAttributes(event: XesEvent): Map<String, Any?> = buildMap {
        putAll(event.customAttributes)
        event.conceptName?.let { putPhysical(Scope.EVENT, StandardAttributeCatalog.CONCEPT_NAME, it) }
        event.conceptInstance?.let { putPhysical(Scope.EVENT, StandardAttributeCatalog.CONCEPT_INSTANCE, it) }
        event.identityId?.let { putPhysical(Scope.EVENT, StandardAttributeCatalog.IDENTITY_ID, it.toString()) }
        physicalEventTimestamp(event)?.let { putPhysical(Scope.EVENT, StandardAttributeCatalog.TIME_TIMESTAMP, it) }
        event.lifecycleTransition?.let { putPhysical(Scope.EVENT, StandardAttributeCatalog.LIFECYCLE_TRANSITION, it) }
        event.lifecycleState?.let { putPhysical(Scope.EVENT, StandardAttributeCatalog.LIFECYCLE_STATE, it) }
        event.orgResource?.let { putPhysical(Scope.EVENT, StandardAttributeCatalog.ORG_RESOURCE, it) }
        event.orgRole?.let { putPhysical(Scope.EVENT, StandardAttributeCatalog.ORG_ROLE, it) }
        event.orgGroup?.let { putPhysical(Scope.EVENT, StandardAttributeCatalog.ORG_GROUP, it) }
        event.costCurrency?.let { putPhysical(Scope.EVENT, StandardAttributeCatalog.COST_CURRENCY, it) }
        event.costTotal?.let { putPhysical(Scope.EVENT, StandardAttributeCatalog.COST_TOTAL, it) }
    }

    fun physicalEventTimestamp(event: XesEvent): LocalDateTime? =
        event.timeTimestamp?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }

    private fun MutableMap<String, Any?>.putPhysical(scope: Scope, canonicalName: String, value: Any?) {
        put(Neo4jXesSchema.physicalName(scope, canonicalName), value)
    }
}
