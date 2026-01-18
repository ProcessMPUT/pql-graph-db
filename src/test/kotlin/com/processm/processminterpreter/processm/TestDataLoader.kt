package com.processm.processminterpreter.processm

import com.processm.processminterpreter.xes.XESLoader
import com.processm.processminterpreter.xes.XESParser
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.*

/**
 * Loads test data from XES files into Neo4j for ProcessM compatibility tests
 */
@Component
class TestDataLoader {
    private val logger = LoggerFactory.getLogger(TestDataLoader::class.java)

    @Autowired
    private lateinit var xesParser: XESParser

    @Autowired
    private lateinit var xesLoader: XESLoader

    // Track loaded logs to avoid re-loading
    private val loadedLogs = mutableMapOf<String, String>()

    /**
     * Load JournalReview-extra.xes test log
     * Returns the log ID in Neo4j
     */
    fun loadJournalReviewLog(): String {
        return loadedLogs.getOrPut("JournalReview") {
            logger.info("Loading JournalReview-extra.xes test data...")

            val stream = javaClass.getResourceAsStream("/JournalReview-extra.xes")
                ?: throw RuntimeException("Cannot find JournalReview-extra.xes in test resources")

            stream.use {
                try {
                    // Load XES file into Neo4j
                    val result = xesLoader.loadXESFile(it, "JournalReview-test")
                    if (!result.success || result.logId == null) {
                        throw RuntimeException("Failed to load JournalReview-extra.xes: ${result.message}")
                    }
                    logger.info("Loaded JournalReview-extra.xes into Neo4j: ${result.tracesCount} traces, logId: ${result.logId}")

                    result.logId!!
                } catch (e: Exception) {
                    logger.error("Failed to load JournalReview-extra.xes", e)
                    throw RuntimeException("Failed to load test data: ${e.message}", e)
                }
            }
        }
    }

    /**
     * Load Hospital log (if available)
     */
    fun loadHospitalLog(): String? {
        return try {
            loadedLogs.getOrPut("Hospital") {
                logger.info("Loading Hospital.xes test data...")

                val stream = javaClass.getResourceAsStream("/Hospital.xes")
                    ?: return null // Hospital log not available

                stream.use {
                    val result = xesLoader.loadXESFile(it, "Hospital-test")
                    if (!result.success || result.logId == null) {
                        return null
                    }
                    logger.info("Loaded Hospital.xes into Neo4j: ${result.tracesCount} traces, logId: ${result.logId}")
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
                logger.info("Loading BPI Challenge 2013 log...")

                val stream = javaClass.getResourceAsStream("/BPI_Challenge_2013_open_problems.xes")
                    ?: return null

                stream.use {
                    val result = xesLoader.loadXESFile(it, "BPI-test")
                    if (!result.success || result.logId == null) {
                        return null
                    }
                    logger.info("Loaded BPI Challenge 2013 into Neo4j: ${result.tracesCount} traces, logId: ${result.logId}")
                    result.logId!!
                }
            }
        } catch (e: Exception) {
            logger.warn("BPI Challenge 2013 log not available or failed to load: ${e.message}")
            null
        }
    }

    /**
     * Clear all test data from Neo4j
     */
    fun clearTestData() {
        logger.info("Clearing test data from Neo4j...")
        // TODO: Implement if needed
        loadedLogs.clear()
    }
}