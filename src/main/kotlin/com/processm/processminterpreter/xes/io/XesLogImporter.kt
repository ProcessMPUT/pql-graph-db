package com.processm.processminterpreter.xes.io

import com.processm.processminterpreter.xes.LogImportResult
import org.springframework.stereotype.Component
import java.io.InputStream

/**
 * Adapter over [XESLoader]: XES-specific parsing and Neo4j batching stay inside
 * the loader, callers get a transport-neutral [LogImportResult].
 */
@Component
class XesLogImporter(private val loader: XESLoader) {
    fun import(input: InputStream, logId: String? = null): LogImportResult {
        val result = loader.loadXESFile(input, logId)
        return LogImportResult(
            success = result.success,
            logId = result.logId,
            traceCount = result.tracesCount,
            eventCount = result.eventsCount,
            message = result.message,
            error = result.error,
        )
    }
}
