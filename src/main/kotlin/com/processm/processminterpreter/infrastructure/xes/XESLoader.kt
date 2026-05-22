package com.processm.processminterpreter.infrastructure.xes

import com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.Neo4jXesLogWriter
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.InputStream
import java.time.LocalDateTime

/**
 * Import facade for XES input sources.
 *
 * Reading XML and writing Neo4j are separate adapters. This class keeps the
 * historical file/resource API used by web endpoints and integration tests,
 * while delegating actual parsing and persistence to focused collaborators.
 */
@Service
class XESLoader(
    private val xesReader: OpenXesReader,
    private val xesLogWriter: Neo4jXesLogWriter,
    private val inputStreams: XesInputStreams = XesInputStreams(),
) {
    private val logger = LoggerFactory.getLogger(XESLoader::class.java)

    fun loadXESFile(
        inputStream: InputStream,
        logId: String? = null,
    ): XESLoadResult {
        logger.info("Starting XES file loading for logId: $logId")

        return try {
            val logs = inputStreams.openPossiblyGzipped(inputStream).use { stream ->
                xesReader.read(stream)
            }
            require(logs.size == 1) { "Expected a single XES log per file, got ${logs.size}" }

            val result = xesLogWriter.write(logs.single(), logId)
            XESLoadResult(
                success = true,
                logId = result.logId,
                tracesCount = result.tracesCount,
                eventsCount = result.eventsCount,
                message = "XES file loaded successfully",
            )
        } catch (e: Exception) {
            logger.error("Error loading XES file", e)
            XESLoadResult(
                success = false,
                error = e.cause?.message ?: e.message ?: "Unknown error occurred",
                message = "Failed to load XES file",
            )
        }
    }

    fun loadXESFromResource(
        resourcePath: String,
        logId: String? = null,
    ): XESLoadResult {
        logger.info("Loading XES from resource: $resourcePath")

        return try {
            inputStreams.openClasspathResource(resourcePath).use { stream ->
                loadXESFile(stream, logId)
            }
        } catch (e: Exception) {
            logger.error("Error loading XES from resource: $resourcePath", e)
            XESLoadResult(
                success = false,
                error = e.cause?.message ?: e.message ?: "Unknown error occurred",
                message = "Failed to load XES from resource",
            )
        }
    }
}

data class XESLoadResult(
    val success: Boolean,
    val logId: String? = null,
    val tracesCount: Int = 0,
    val eventsCount: Int = 0,
    val message: String,
    val error: String? = null,
    val filename: String? = null,
    val timestamp: LocalDateTime = LocalDateTime.now(),
)
