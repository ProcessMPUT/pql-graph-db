package com.processm.processminterpreter.application.log

import com.processm.processminterpreter.application.ports.LogRepository
import com.processm.processminterpreter.application.ports.LogStatistics
import com.processm.processminterpreter.domain.log.Classifier
import com.processm.processminterpreter.domain.log.Extension
import com.processm.processminterpreter.domain.log.GlobalAttribute
import com.processm.processminterpreter.domain.log.Log
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID

@Component
class LogUseCases(private val logs: LogRepository) {
    fun create(request: CreateLogRequest): Log {
        require(request.name.isNotBlank()) { "Log name cannot be blank" }
        val id = request.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        require(!logs.exists(id)) { "Log with ID '$id' already exists" }
        val now = LocalDateTime.now()
        return logs.save(
            Log(
                id = id,
                name = request.name,
                createdAt = now,
                updatedAt = now,
                lifecycleModel = request.lifecycleModel,
                classifiers = request.classifiers,
                extensions = request.extensions,
                traceGlobals = request.traceGlobals,
                eventGlobals = request.eventGlobals,
                customAttributes = request.customAttributes,
            ),
        )
    }

    fun get(id: String): Log = logs.findById(id) ?: throw LogNotFoundException(id)

    fun find(id: String): Log? = logs.findById(id)

    fun statistics(id: String): LogStatistics? = logs.getStatistics(id)

    fun list(): List<Log> = logs.findAll()

    fun listWithStatistics(): List<Pair<Log, LogStatistics>> = logs.getStatisticsAll()

    fun update(request: UpdateLogRequest): Log {
        val existing = logs.findById(request.id) ?: throw LogNotFoundException(request.id)
        val updated = existing.copy(
            name = request.name ?: existing.name,
            updatedAt = LocalDateTime.now(),
            lifecycleModel = request.lifecycleModel ?: existing.lifecycleModel,
            classifiers = request.classifiers ?: existing.classifiers,
            extensions = request.extensions ?: existing.extensions,
            traceGlobals = request.traceGlobals ?: existing.traceGlobals,
            eventGlobals = request.eventGlobals ?: existing.eventGlobals,
            customAttributes = request.customAttributes ?: existing.customAttributes,
        )
        return logs.update(updated)
    }

    fun delete(id: String): Boolean = logs.delete(id)

    fun deleteWithData(id: String): Boolean = logs.deleteWithData(id)

    fun search(request: SearchLogsRequest): List<Log> = when {
        request.namePart != null -> logs.search(request.namePart)
        request.attribute != null -> logs.findByAttribute(request.attribute.key, request.attribute.value)
        request.createdAfter != null && request.createdBefore != null ->
            logs.findCreatedBetween(request.createdAfter, request.createdBefore)
        request.createdAfter != null -> logs.findCreatedAfter(request.createdAfter)
        else -> logs.findAll()
    }
}

data class CreateLogRequest(
    val name: String,
    val id: String? = null,
    val lifecycleModel: String? = null,
    val classifiers: List<Classifier> = emptyList(),
    val extensions: List<Extension> = emptyList(),
    val traceGlobals: List<GlobalAttribute> = emptyList(),
    val eventGlobals: List<GlobalAttribute> = emptyList(),
    val customAttributes: Map<String, Any?> = emptyMap(),
)

data class UpdateLogRequest(
    val id: String,
    val name: String? = null,
    val lifecycleModel: String? = null,
    val classifiers: List<Classifier>? = null,
    val extensions: List<Extension>? = null,
    val traceGlobals: List<GlobalAttribute>? = null,
    val eventGlobals: List<GlobalAttribute>? = null,
    val customAttributes: Map<String, Any?>? = null,
)

data class SearchLogsRequest(
    val namePart: String? = null,
    val attribute: AttributeFilter? = null,
    val createdAfter: LocalDateTime? = null,
    val createdBefore: LocalDateTime? = null,
)

data class AttributeFilter(val key: String, val value: Any)
