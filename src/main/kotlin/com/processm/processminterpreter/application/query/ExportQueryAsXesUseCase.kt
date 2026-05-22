package com.processm.processminterpreter.application.query

import com.processm.processminterpreter.application.ports.XesWriter
import com.processm.processminterpreter.application.ports.XesWriteOptions
import com.processm.processminterpreter.domain.pql.catalog.Scope
import com.processm.processminterpreter.domain.pql.plan.HierarchicalLimits
import org.springframework.stereotype.Component
import java.io.OutputStream

/**
 * Runs a PQL query and streams the result straight out as XES XML.
 *
 * Composed from [ExecutePqlQueryUseCase] + [XesWriter]:
 *  - the execute step returns [XesLog] projections already reconstructed,
 *  - the writer serializes them to the caller-provided [OutputStream].
 *
 * Kept as a distinct use case so controllers / export endpoints can stream
 * responses without materializing the whole XML into memory as a string first.
 * DELETE queries aren't a valid export target — reject early so callers
 * can't silently emit an empty XES file instead of seeing their mistake.
 */
@Component
class ExportQueryAsXesUseCase(
    private val executeQuery: ExecutePqlQueryUseCase,
    private val writer: XesWriter,
) {

    fun export(request: ExportQueryAsXesRequest, output: OutputStream): ExportResult {
        val query = request.query.trim()
        require(!query.lowercase().startsWith("delete")) {
            "DELETE queries cannot be exported as XES; use ExecutePqlQueryUseCase.executeDelete instead"
        }

        val result = executeQuery.execute(
            ExecutePqlQueryRequest(
                query = request.query,
                logId = request.logId,
                dataStoreId = request.dataStoreId,
                defaultLimits = request.defaultLimits,
                materializedScopes = setOf(Scope.LOG, Scope.TRACE, Scope.EVENT),
            ),
        )
        writer.write(
            logs = result.logs,
            output = output,
            options = XesWriteOptions(compress = request.compress, logName = request.logName),
        )
        return ExportResult(
            logCount = result.logs.size,
            rowCount = result.rowCount,
            executedQueryDescription = result.executedQueryDescription,
        )
    }
}

data class ExportQueryAsXesRequest(
    val query: String,
    val logId: String? = null,
    val dataStoreId: String? = null,
    val defaultLimits: HierarchicalLimits = HierarchicalLimits(),
    val compress: Boolean = false,
    val logName: String = "Query Result Log",
)

data class ExportResult(
    val logCount: Int,
    val rowCount: Int,
    val executedQueryDescription: String,
)
