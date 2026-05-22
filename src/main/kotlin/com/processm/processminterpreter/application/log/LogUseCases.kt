package com.processm.processminterpreter.application.log

import com.processm.processminterpreter.domain.log.Classifier
import com.processm.processminterpreter.domain.log.Extension
import com.processm.processminterpreter.domain.log.GlobalAttribute
import com.processm.processminterpreter.domain.log.Log
import com.processm.processminterpreter.application.ports.LogRepository
import com.processm.processminterpreter.application.ports.LogStatistics
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.UUID

/**
 * Thin hex-architecture use cases wrapping [LogRepository]. Each one is a single
 * behavior — a controller layer composes them as needed instead of calling the
 * repository directly. Kept as discrete `@Component`s so Spring can inject them
 * individually and tests can stub one without pulling in the others.
 */

// ----- Create -----

@Component
class CreateLogUseCase(private val logs: LogRepository) {
    fun create(request: CreateLogRequest): Log {
        require(request.name.isNotBlank()) { "Log name cannot be blank" }
        val id = request.id?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        if (logs.exists(id)) {
            throw IllegalArgumentException("Log with ID '$id' already exists")
        }
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

// ----- Get -----

@Component
class GetLogUseCase(private val logs: LogRepository) {
    fun get(id: String): Log = logs.findById(id) ?: throw LogNotFoundException(id)

    /** Non-throwing variant for callers that handle absence inline. */
    fun find(id: String): Log? = logs.findById(id)

    fun statistics(id: String): LogStatistics? = logs.getStatistics(id)
}

// ----- List -----

@Component
class ListLogsUseCase(private val logs: LogRepository) {
    fun list(): List<Log> = logs.findAll()
    fun listWithStatistics(): List<Pair<Log, LogStatistics>> = logs.getStatisticsAll()
}

// ----- Update -----

@Component
class UpdateLogUseCase(private val logs: LogRepository) {
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
}

/** Nullable fields are "leave alone" — non-null fields are the replacement value. */
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

// ----- Delete -----

@Component
class DeleteLogUseCase(private val logs: LogRepository) {
    /** Delete metadata only — traces/events left orphaned. Matches legacy `deleteLog`. */
    fun delete(id: String): Boolean = logs.delete(id)

    /** Delete log plus all traces/events underneath. Matches legacy `deleteLogWithData`. */
    fun deleteWithData(id: String): Boolean = logs.deleteWithData(id)
}

// ----- Search -----

@Component
class SearchLogsUseCase(private val logs: LogRepository) {
    fun search(request: SearchLogsRequest): List<Log> = when {
        request.namePart != null -> logs.search(request.namePart)
        request.attribute != null -> logs.findByAttribute(request.attribute.key, request.attribute.value)
        request.createdAfter != null && request.createdBefore != null ->
            logs.findCreatedBetween(request.createdAfter, request.createdBefore)
        request.createdAfter != null -> logs.findCreatedAfter(request.createdAfter)
        else -> logs.findAll()
    }
}

data class SearchLogsRequest(
    val namePart: String? = null,
    val attribute: AttributeFilter? = null,
    val createdAfter: LocalDateTime? = null,
    val createdBefore: LocalDateTime? = null,
)

data class AttributeFilter(val key: String, val value: Any)
