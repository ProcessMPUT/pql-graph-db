package com.processm.processminterpreter.infrastructure.xes

import com.processm.processminterpreter.application.ports.LogDataImporter
import com.processm.processminterpreter.application.ports.LogImportResult
import org.springframework.stereotype.Component
import java.io.InputStream

/**
 * [LogDataImporter] adapter over [XESLoader].
 *
 * It keeps the use case layer on a port while the XES-specific parsing and
 * Neo4j batching stay inside the infrastructure adapter.
 */
@Component
class XesLogImporter(private val loader: XESLoader) : LogDataImporter {
    override fun import(input: InputStream, logId: String?): LogImportResult {
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
