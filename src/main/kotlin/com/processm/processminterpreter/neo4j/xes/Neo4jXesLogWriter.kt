package com.processm.processminterpreter.neo4j.xes

import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.neo4j.xes.mapping.Neo4jXesImportMapper
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesSchemaInitializer
import com.processm.processminterpreter.neo4j.xes.writing.Neo4jXesBatchWriter
import org.neo4j.driver.Driver
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Persists a domain [XesLog] hierarchy into the Neo4j XES graph shape.
 */
@Component
class Neo4jXesLogWriter @Autowired constructor(
    private val mapper: Neo4jXesImportMapper,
    private val batchWriter: Neo4jXesBatchWriter,
    private val schemaInitializer: Neo4jXesSchemaInitializer,
) {
    constructor(driver: Driver) : this(
        mapper = Neo4jXesImportMapper(),
        batchWriter = Neo4jXesBatchWriter(driver),
        schemaInitializer = Neo4jXesSchemaInitializer(driver),
    )

    init {
        schemaInitializer.ensureIndexes()
    }

    private val logger = LoggerFactory.getLogger(Neo4jXesLogWriter::class.java)

    fun write(
        log: XesLog,
        requestedLogId: String?,
    ): Neo4jXesLogWriteResult {
        val batch = mapper.toImportBatch(
            log = log,
            requestedLogId = requestedLogId,
            importedAt = LocalDateTime.now(),
        )

        logger.info("Saving XES log to Neo4j: ${batch.logId}. Total traces: ${batch.traceCount}")
        batchWriter.write(batch)
        logger.info("Finished saving XES log to Neo4j: ${batch.logId}")

        return Neo4jXesLogWriteResult(
            logId = batch.logId,
            tracesCount = batch.traceCount,
            eventsCount = batch.eventCount,
        )
    }
}

data class Neo4jXesLogWriteResult(
    val logId: String,
    val tracesCount: Int,
    val eventsCount: Int,
)
