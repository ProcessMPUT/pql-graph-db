package com.processm.processminterpreter.neo4j.repository

import com.processm.processminterpreter.xes.LogNotFoundException
import com.processm.processminterpreter.xes.model.Log
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.GraphDatabase
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.neo4j.Neo4jContainer
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jLogRepositoryDeleteTest {
    companion object {
        @Container
        val neo4jContainer =
            Neo4jContainer("neo4j:2026.07.1-community-ubi10")
                .withAdminPassword("password")

        /** More events than one 5k deletion batch, so the test exercises batching. */
        private const val TRACES = 30
        private const val EVENTS_PER_TRACE = 1000
    }

    private lateinit var driver: Driver
    private lateinit var logs: Neo4jLogRepository
    private lateinit var dataStores: Neo4jDataStoreRepository

    @BeforeAll
    fun setUp() {
        driver = GraphDatabase.driver(
            neo4jContainer.boltUrl,
            AuthTokens.basic("neo4j", "password"),
        )
        logs = Neo4jLogRepository(driver)
        dataStores = Neo4jDataStoreRepository(driver)
    }

    @AfterAll
    fun tearDown() {
        driver.close()
    }

    @BeforeEach
    fun clearDatabase() {
        driver.session().use { it.run("MATCH (n) DETACH DELETE n").consume() }
    }

    private fun seedLog(logId: String) {
        driver.session().use { session ->
            session.run(
                """
                CREATE (l:Log {logId: ${'$'}logId, name: 'big'})
                WITH l
                UNWIND range(1, ${'$'}traces) AS ti
                CREATE (l)-[:CONTAINS]->(t:Trace {traceId: ${'$'}logId + '-t' + ti, caseId: 'c' + ti})
                WITH t, ti
                UNWIND range(1, ${'$'}events) AS ei
                CREATE (t)-[:HAS_EVENT]->(:Event {eventId: t.traceId + '-e' + ei, activity: 'A'})
                """.trimIndent(),
                mapOf("logId" to logId, "traces" to TRACES, "events" to EVENTS_PER_TRACE),
            ).consume()
        }
    }

    private fun counts(): Triple<Long, Long, Long> =
        driver.session().use { session ->
            val record = session.run(
                "OPTIONAL MATCH (l:Log) WITH count(l) AS logs" +
                    " OPTIONAL MATCH (t:Trace) WITH logs, count(t) AS traces" +
                    " OPTIONAL MATCH (e:Event) RETURN logs, traces, count(e) AS events",
            ).single()
            Triple(record["logs"].asLong(), record["traces"].asLong(), record["events"].asLong())
        }

    @Test
    fun `deleteWithData removes the whole subtree in batches without orphans`() {
        seedLog("log-big")
        assertEquals(Triple(1L, TRACES.toLong(), (TRACES * EVENTS_PER_TRACE).toLong()), counts())

        assertTrue(logs.deleteWithData("log-big"))

        assertEquals(Triple(0L, 0L, 0L), counts())
        assertFalse(logs.deleteWithData("log-big"), "second delete should report missing log")
    }

    @Test
    fun `delete without data flag also leaves no orphaned traces or events`() {
        seedLog("log-orphan-check")

        assertTrue(logs.delete("log-orphan-check"))

        assertEquals(Triple(0L, 0L, 0L), counts())
    }

    @Test
    fun `deleteWithLogs removes datastore with all contained log subtrees`() {
        seedLog("log-in-store")
        driver.session().use { session ->
            session.run(
                "CREATE (ds:DataStore {dataStoreId: 'store-1', name: 's'})" +
                    " WITH ds MATCH (l:Log {logId: 'log-in-store'}) CREATE (ds)-[:CONTAINS_LOG]->(l)",
            ).consume()
        }

        assertTrue(dataStores.deleteWithLogs("store-1"))

        assertEquals(Triple(0L, 0L, 0L), counts())
        driver.session().use { session ->
            val stores = session.run("MATCH (ds:DataStore) RETURN count(ds) AS c").single()["c"].asLong()
            assertEquals(0L, stores)
        }
    }

    @Test
    fun `search finds custom attributes whose XES names require physical encoding`() {
        val now = LocalDateTime.parse("2026-08-14T12:00:00")
        logs.save(
            Log(
                id = "encoded-search",
                name = "search",
                createdAt = now,
                updatedAt = now,
                customAttributes = mapOf(
                    "createdAt" to "source-created-at",
                    "field.with.dots" to "dotted-value",
                ),
            ),
        )

        assertEquals(listOf("encoded-search"), logs.findByAttribute("createdAt", "source-created-at").map { it.id })
        assertEquals(listOf("encoded-search"), logs.findByAttribute("field.with.dots", "dotted-value").map { it.id })
    }

    @Test
    fun `update atomically replaces custom attributes instead of retaining stale properties`() {
        val now = LocalDateTime.parse("2026-08-14T12:00:00")
        val original = Log(
            id = "updated-log",
            name = "before",
            createdAt = now,
            updatedAt = now,
            lifecycleModel = "standard",
            customAttributes = mapOf("old" to "stale", "createdAt" to "custom"),
        )
        logs.save(original)

        val updated = logs.update(
            original.copy(
                name = "after",
                lifecycleModel = null,
                customAttributes = mapOf("new" to "fresh"),
            ),
        )

        assertEquals("after", updated.name)
        assertEquals(mapOf("new" to "fresh"), updated.customAttributes)
        assertNull(updated.lifecycleModel)
        assertEquals(updated, logs.findById(original.id))
    }

    @Test
    fun `update does not recreate a missing log`() {
        val now = LocalDateTime.parse("2026-08-14T12:00:00")

        assertFailsWith<LogNotFoundException> {
            logs.update(Log("missing", "missing", now, now))
        }
        assertFalse(logs.exists("missing"))
    }
}
