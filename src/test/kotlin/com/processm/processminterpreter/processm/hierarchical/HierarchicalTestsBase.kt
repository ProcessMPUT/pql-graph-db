package com.processm.processminterpreter.processm.hierarchical

import com.processm.processminterpreter.model.hierarchical.Event
import com.processm.processminterpreter.model.hierarchical.Log
import com.processm.processminterpreter.model.hierarchical.Trace
import com.processm.processminterpreter.processm.TestDataLoader
import com.processm.processminterpreter.service.PQLQueryResult
import com.processm.processminterpreter.service.PQLQueryService
import org.neo4j.driver.Driver
import org.springframework.beans.factory.annotation.Autowired
import java.time.Instant
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
     * Load test data. All hierarchical test classes share a single logId to avoid
     * accumulating multiple copies of the same dataset in Neo4j.
     */
    protected fun loadTestDataWithUniqueId(): String {
        println("Loading test data with logId: $JOURNAL_LOG_ID")
        return testDataLoader.loadJournalReviewLog(JOURNAL_LOG_ID)
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
        /** Sentinel value: no default trace limit (matches ProcessM test behavior) */
        const val NO_DEFAULT_LIMIT = -1

        const val JOURNAL_LOG_ID = "JournalReview-test"

        const val TOTAL_TRACES = 101
        const val TOTAL_EVENTS = 2298L

        // Event names from JournalReview-extra.xes
        val eventNames =
            setOf(
                "invite reviewers",
                "time-out 1",
                "time-out 2",
                "time-out 3",
                "get review 1",
                "get review 2",
                "get review 3",
                "collect reviews",
                "decide",
                "invite additional reviewer",
                "get review X",
                "time-out X",
                "reject",
                "accept",
            )

        val lifecycleTransitions = setOf("start", "complete")
        val orgResources = setOf("__INVALID__", "Mike", "Anne", "Wil", "Pete", "John", "Mary", "Carol", "Sara", "Sam", "Pam")
        val results = setOf("accept", "reject")
        val validCurrencies = setOf("EUR", "USD")
        val bpiEventNames = setOf("Accepted", "Completed", "Queued")

        // Time range for JournalReview log
        val begin: Instant = Instant.parse("2005-12-31T00:00:00.000Z")
        val end: Instant = Instant.parse("2008-05-05T00:00:00.000Z")
    }

    /**
     * Standard log-level assertions matching ProcessM's DBHierarchicalXESInputStreamWithQueryTestsBase.
     * Validates conceptName = "JournalReview" and lifecycle:model = "standard".
     */
    protected fun standardLogAssertions(log: Log) {
        assertEquals("JournalReview", log.conceptName, "Log conceptName should be 'JournalReview'")
        assertEquals("standard", log.lifecycleModel, "Log lifecycle:model should be 'standard'")
    }

    /**
     * Standard trace-level assertions matching ProcessM originals.
     * Validates cost:currency is EUR or null, cost:total is null or > 0,
     * conceptName is parseable as int in -1..100, isEventStream is false,
     * and identityId is null (JournalReview traces have no identity:id).
     */
    protected fun standardTraceAssertions(trace: Trace) {
        val currency = trace.costCurrency
        assertTrue(
            currency == null || currency == "EUR",
            "Trace cost:currency should be EUR or null, got: $currency",
        )

        val total = trace.costTotal
        assertTrue(
            total == null || total > 0.0,
            "Trace cost:total should be null or > 0, got: $total",
        )

        val name = trace.conceptName
        assertNotNull(name, "Trace conceptName should not be null")
        val parsed = name.toIntOrNull()
        assertNotNull(parsed, "Trace conceptName should be parseable as int, got: $name")
        assertTrue(
            parsed in -1..100,
            "Trace conceptName (as int) should be in -1..100, got: $parsed",
        )

        assertFalse(trace.isEventStream, "Trace isEventStream should be false")
        assertNull(trace.identityId, "Trace identity:id should be null for JournalReview traces")
    }

    /**
     * Standard event-level assertions matching ProcessM originals.
     * Validates conceptName in eventNames, timestamp in range, cost:total 1.0-1.08,
     * conceptInstance parseable as int, costCurrency in validCurrencies,
     * lifecycle:state null, org:group null, org:role null, identity:id null,
     * org:resource in orgResources, lifecycle:transition in lifecycleTransitions.
     */
    protected fun standardEventAssertions(event: Event) {
        assertTrue(
            event.conceptName in eventNames,
            "Event conceptName should be in eventNames, got: ${event.conceptName}",
        )

        assertNotNull(event.timeTimestamp, "Event should have timestamp")
        assertTrue(
            event.timeTimestamp!!.isAfter(begin),
            "Event timestamp should be after begin, got: ${event.timeTimestamp}",
        )
        assertTrue(
            event.timeTimestamp!!.isBefore(end),
            "Event timestamp should be before end, got: ${event.timeTimestamp}",
        )

        // Note: ProcessM's JournalReview-extra.xes has concept:instance; ours does not.
        // If present, it must be parseable as int:
        if (event.conceptInstance != null) {
            assertNotNull(
                event.conceptInstance!!.toIntOrNull(),
                "Event concept:instance should be parseable as int, got: ${event.conceptInstance}",
            )
        }

        assertTrue(
            event.costCurrency in validCurrencies,
            "Event cost:currency should be EUR or USD, got: ${event.costCurrency}",
        )

        val cost = event.costTotal
        assertNotNull(cost, "Event cost:total should not be null")
        assertTrue(
            cost in 1.0..1.08,
            "Event cost:total should be in 1.0..1.08, got: $cost",
        )

        assertNull(event.lifecycleState, "Event lifecycle:state should be null")
        assertNull(event.orgGroup, "Event org:group should be null")
        assertNull(event.orgRole, "Event org:role should be null")
        assertNull(event.identityId, "Event identity:id should be null")

        assertNotNull(event.orgResource, "Event org:resource should not be null")
        assertTrue(
            event.orgResource in orgResources,
            "Event org:resource should be in orgResources, got: ${event.orgResource}",
        )

        assertNotNull(event.lifecycleTransition, "Event lifecycle:transition should not be null")
        assertTrue(
            event.lifecycleTransition in lifecycleTransitions,
            "Event lifecycle:transition should be in lifecycleTransitions, got: ${event.lifecycleTransition}",
        )
    }

    /**
     * Standard log-level checks for SELECT * queries (where all log attributes are present).
     * Validates conceptName, lifecycleModel, source, description, classifiers, globals.
     * Use only when SELECT * is expected (not partial SELECT queries).
     */
    protected fun standardLogAssertionsWithMetadata(log: Log) {
        standardLogAssertions(log)
        assertTrue(
            log.attributes["source"].let { it is String && it == "CPN Tools" },
            "Log source should be 'CPN Tools', got: ${log.attributes["source"]}",
        )
        assertTrue(
            log.attributes["description"].let { it is String && it == "Log file created in CPN Tools" },
            "Log description should be 'Log file created in CPN Tools', got: ${log.attributes["description"]}",
        )
        assertEquals(3, log.eventClassifiers.size, "Should have 3 event classifiers")
        assertEquals(2, log.eventGlobals.size, "Should have 2 event globals")
        assertEquals(1, log.traceGlobals.size, "Should have 1 trace global")
    }

    /**
     * Execute PQL query against journal log.
     * No default trace limit applied — matches ProcessM test behavior
     * (ProcessM tests pass limit=emptyMap() to DBHierarchicalXESInputStream).
     */
    protected fun q(
        query: String,
        logId: String,
    ): PQLQueryResult = pqlQueryService.executePQLQuery(query, logId, defaultTraceLimit = NO_DEFAULT_LIMIT)

    /**
     * Execute PQL query without log filter
     */
    protected fun q(query: String): PQLQueryResult = pqlQueryService.executePQLQuery(query, defaultTraceLimit = NO_DEFAULT_LIMIT)

    /**
     * Parse ISO8601 datetime string
     */
    protected fun parseISO8601(dateStr: String): Instant = Instant.parse(dateStr)

    /**
     * Convert Instant to ZonedDateTime (UTC)
     */
    protected fun Instant.toDateTime(): ZonedDateTime = this.atZone(java.time.ZoneOffset.UTC)

    /**
     * Compare nullable comparable values (nulls last)
     */
    protected fun <T : Comparable<T>> cmp(
        a: T?,
        b: T?,
    ): Int {
        if (a === b) return 0
        if (a == null) return 1
        if (b == null) return -1
        return a.compareTo(b)
    }

    /**
     * Check if instant is within range
     */
    protected fun Instant.isInRange(
        start: Instant,
        end: Instant,
    ): Boolean = !this.isBefore(start) && !this.isAfter(end)
}
