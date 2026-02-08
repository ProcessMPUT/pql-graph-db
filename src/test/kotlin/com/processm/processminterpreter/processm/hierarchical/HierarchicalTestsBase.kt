package com.processm.processminterpreter.processm.hierarchical

import com.processm.processminterpreter.processm.TestDataLoader
import com.processm.processminterpreter.service.PQLQueryResult
import com.processm.processminterpreter.service.PQLQueryService
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * Base class for PQL query integration tests
 *
 * Mirrors ProcessM's DBHierarchicalXESInputStreamWithQueryTestsBase
 * Provides test data loading and common test utilities
 */
abstract class HierarchicalTestsBase {

    @Autowired
    protected lateinit var pqlQueryService: PQLQueryService

    @Autowired
    protected lateinit var testDataLoader: TestDataLoader

    @Autowired
    protected lateinit var neo4jDriver: Driver

    /**
     * Load test data with a unique logId for this test class.
     * Uses class name to generate unique logId to prevent test interference.
     */
    protected fun loadTestDataWithUniqueId(): String {
        val uniqueLogId = "JournalReview-${this::class.simpleName}"
        println("Loading test data with unique logId: $uniqueLogId")
        return testDataLoader.loadJournalReviewLog(uniqueLogId)
    }

    /**
     * Clear database and load test data.
     * Use only when you need a fresh database state.
     */
    protected fun clearAndLoadTestData(): String {
        println("Clearing Neo4j database...")
        neo4jDriver.session().use { session ->
            session.run("MATCH (n) DETACH DELETE n").consume()
        }
        println("Database cleared")

        return testDataLoader.loadJournalReviewLog()
    }

    companion object {
        // Event names from JournalReview-extra.xes
        val eventNames = setOf(
            "invite reviewers",
            "time-out 1", "time-out 2", "time-out 3",
            "get review 1", "get review 2", "get review 3",
            "collect reviews",
            "decide",
            "invite additional reviewer",
            "get review X",
            "time-out X",
            "reject",
            "accept"
        )

        val lifecycleTransitions = setOf("start", "complete")
        val orgResources = setOf("__INVALID__", "Mike", "Anne", "Wil", "Pete", "John", "Mary", "Carol", "Sara", "Sam", "Pam")
        val results = setOf("accept", "reject")
        val validCurrencies = setOf("EUR", "USD")

        // Time range for JournalReview log
        val begin: Instant = Instant.parse("2005-12-31T00:00:00.000Z")
        val end: Instant = Instant.parse("2008-05-05T00:00:00.000Z")
    }

    /**
     * Execute PQL query against journal log
     */
    protected fun q(query: String, logId: String): PQLQueryResult {
        return pqlQueryService.executePQLQuery(query, logId)
    }

    /**
     * Execute PQL query without log filter
     */
    protected fun q(query: String): PQLQueryResult {
        return pqlQueryService.executePQLQuery(query)
    }

    /**
     * Parse ISO8601 datetime string
     */
    protected fun parseISO8601(dateStr: String): Instant {
        return Instant.parse(dateStr)
    }

    /**
     * Convert Instant to ZonedDateTime (UTC)
     */
    protected fun Instant.toDateTime(): ZonedDateTime {
        return this.atZone(java.time.ZoneOffset.UTC)
    }

    /**
     * Compare nullable comparable values (nulls last)
     */
    protected fun <T : Comparable<T>> cmp(a: T?, b: T?): Int {
        if (a === b) return 0
        if (a == null) return 1
        if (b == null) return -1
        return a.compareTo(b)
    }

    /**
     * Check if instant is within range
     */
    protected fun Instant.isInRange(start: Instant, end: Instant): Boolean {
        return !this.isBefore(start) && !this.isAfter(end)
    }
}
