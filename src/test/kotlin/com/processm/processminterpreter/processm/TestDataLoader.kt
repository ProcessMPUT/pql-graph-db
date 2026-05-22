package com.processm.processminterpreter.processm

import com.processm.processminterpreter.application.ports.DataStoreRepository
import com.processm.processminterpreter.domain.datastore.DataStore
import com.processm.processminterpreter.infrastructure.xes.XESLoader
import org.neo4j.driver.Driver
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Loads test data from XES files into Neo4j for ProcessM compatibility tests
 */
@Component
class TestDataLoader {
    private val logger = LoggerFactory.getLogger(TestDataLoader::class.java)

    @Autowired
    private lateinit var xesLoader: XESLoader

    @Autowired
    private lateinit var neo4jDriver: Driver

    @Autowired
    private lateinit var dataStores: DataStoreRepository

    // Track loaded logs to avoid re-loading
    private val loadedLogs = mutableMapOf<String, String>()

    companion object {
        const val PROCESSM_COMPAT_DATA_STORE_ID = "processm-compat-tests"
    }

    /**
     * Load JournalReview-extra.xes test log
     * Returns the log ID in Neo4j
     *
     * @param customLogId optional custom log ID (default: "JournalReview-test")
     */
    fun loadJournalReviewLog(customLogId: String = "JournalReview-test"): String {
        return loadedLogs.getOrPut(customLogId) {
            ensureCompatibilityDataStore()
            // Check if log already exists in Neo4j (from previous test run)
            val existingLogId = checkLogExists(customLogId)
            if (existingLogId != null) {
                logger.info("Log already exists in Neo4j: $customLogId")
                dataStores.attachLog(PROCESSM_COMPAT_DATA_STORE_ID, existingLogId)
                return@getOrPut existingLogId
            }

            logger.info("Loading JournalReview-extra.xes test data with logId: $customLogId...")

            val stream =
                javaClass.getResourceAsStream("/JournalReview-extra.xes")
                    ?: throw RuntimeException("Cannot find JournalReview-extra.xes in test resources")

            stream.use {
                try {
                    // Load XES file into Neo4j
                    val result = xesLoader.loadXESFile(it, customLogId)
                    if (!result.success || result.logId == null) {
                        throw RuntimeException("Failed to load JournalReview-extra.xes: ${result.message}")
                    }
                    logger.info("Loaded JournalReview-extra.xes into Neo4j: ${result.tracesCount} traces, logId: ${result.logId}")

                    dataStores.attachLog(PROCESSM_COMPAT_DATA_STORE_ID, result.logId!!)
                    result.logId!!
                } catch (e: Exception) {
                    logger.error("Failed to load JournalReview-extra.xes", e)
                    throw RuntimeException("Failed to load test data: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Check if a log with the given ID already exists in Neo4j
     */
    private fun checkLogExists(logId: String): String? =
        try {
            neo4jDriver.session().use { session ->
                val result =
                    session.run(
                        "MATCH (log:Log {logId: \$logId}) RETURN log.logId as logId LIMIT 1",
                        mapOf("logId" to logId),
                    )
                if (result.hasNext()) {
                    result.single().get("logId").asString()
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            logger.warn("Error checking if log exists: ${e.message}")
            null
        }

    /**
     * Load Hospital log (if available)
     */
    fun loadHospitalLog(): String? {
        return try {
            loadedLogs.getOrPut("Hospital") {
                ensureCompatibilityDataStore()
                // Check Neo4j first — prevents re-loading after JVM restart
                val existing = checkLogExists("Hospital-test")
                if (existing != null) {
                    logger.info("Hospital log already exists in Neo4j: $existing")
                    dataStores.attachLog(PROCESSM_COMPAT_DATA_STORE_ID, existing)
                    return@getOrPut existing
                }

                logger.info("Loading Hospital.xes test data...")
                val stream =
                    javaClass.getResourceAsStream("/logs/Hospital_log.xes")
                        ?: return null // Hospital log not available

                stream.use {
                    val result = xesLoader.loadXESFile(it, "Hospital-test")
                    if (!result.success || result.logId == null) {
                        return null
                    }
                    logger.info("Loaded Hospital.xes into Neo4j: ${result.tracesCount} traces, logId: ${result.logId}")
                    dataStores.attachLog(PROCESSM_COMPAT_DATA_STORE_ID, result.logId!!)
                    result.logId!!
                }
            }
        } catch (e: Exception) {
            logger.warn("Hospital.xes not available or failed to load: ${e.message}")
            null
        }
    }

    /**
     * Load BPI Challenge 2013 log (if available)
     */
    fun loadBPILog(): String? {
        return try {
            loadedLogs.getOrPut("BPI") {
                ensureCompatibilityDataStore()
                // Check Neo4j first — prevents re-loading after JVM restart
                val existing = checkLogExists("BPI-test")
                if (existing != null) {
                    logger.info("BPI log already exists in Neo4j: $existing")
                    dataStores.attachLog(PROCESSM_COMPAT_DATA_STORE_ID, existing)
                    return@getOrPut existing
                }

                logger.info("Loading BPI Challenge 2013 log...")
                val stream =
                    javaClass.getResourceAsStream("/bpi_challenge_2013_open_problems.xes")
                        ?: return null

                stream.use {
                    val result = xesLoader.loadXESFile(it, "BPI-test")
                    if (!result.success || result.logId == null) {
                        return null
                    }
                    logger.info("Loaded BPI Challenge 2013 into Neo4j: ${result.tracesCount} traces, logId: ${result.logId}")
                    dataStores.attachLog(PROCESSM_COMPAT_DATA_STORE_ID, result.logId!!)
                    result.logId!!
                }
            }
        } catch (e: Exception) {
            logger.warn("BPI Challenge 2013 log not available or failed to load: ${e.message}")
            null
        }
    }

    /**
     * Clear all test data from Neo4j.
     * Removes only logs with known test logIds.
     * Call this from @AfterAll if you need a clean database for the next test run.
     */
    fun clearTestData(
        logIds: List<String> = listOf(
            "0f6a2822-6d9a-4dd2-88d7-0f50e10b5f5f",
            "Hospital-test",
            "BPI-test",
        ),
    ) {
        logger.info("Clearing test data for logIds: $logIds")
        neo4jDriver.session().use { session ->
            for (logId in logIds) {
                val deleted =
                    session
                        .run(
                            """
                            MATCH (log:Log {logId: ${'$'}logId})-[:CONTAINS]->(trace:Trace)-[:HAS_EVENT]->(event:Event)
                            DETACH DELETE log, trace, event
                            """.trimIndent(),
                            mapOf("logId" to logId),
                        ).consume()
                        .counters()
                        .nodesDeleted()
                if (deleted > 0) logger.info("Deleted $deleted nodes for logId: $logId")
            }
        }
        loadedLogs.clear()
    }

    private fun ensureCompatibilityDataStore() {
        if (dataStores.exists(PROCESSM_COMPAT_DATA_STORE_ID)) return
        val now = LocalDateTime.now()
        dataStores.save(
            DataStore(
                id = PROCESSM_COMPAT_DATA_STORE_ID,
                name = "ProcessM compatibility tests",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}


