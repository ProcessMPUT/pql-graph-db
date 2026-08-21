package com.processm.processminterpreter.neo4j.query

import org.junit.jupiter.api.Test
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.summary.ProfiledPlan
import org.testcontainers.neo4j.Neo4jContainer
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Neo4jTraceWindowIndexPlanTest {
    @Test
    fun `indexed trace window stops reading after the requested rows`() {
        Neo4jContainer("neo4j:2026.07.1-community-ubi10").withAdminPassword("password").use { neo4j ->
            neo4j.start()
            GraphDatabase.driver(neo4j.boltUrl, AuthTokens.basic("neo4j", "password")).use { driver ->
                driver.session().use { session ->
                    session.run(
                        "CREATE (:Log {logId: 'log-1'}) WITH 1 AS ignored" +
                            " MATCH (log:Log {logId: 'log-1'}) UNWIND range(0, 1999) AS i" +
                            " CREATE (log)-[:CONTAINS]->(:Trace {traceId: 't-' + i, parentLogId: 'log-1', importOrder: i})",
                    ).consume()
                    session.run(
                        "CREATE INDEX trace_parent_import_order IF NOT EXISTS FOR (trace:Trace)" +
                            " ON (trace.parentLogId, trace.importOrder)",
                    ).consume()
                    session.run("CALL db.awaitIndexes()").consume()

                    val profile = session.run(
                        "PROFILE MATCH (log:Log {logId: 'log-1'})" +
                            " CALL (log) { MATCH (trace:Trace {parentLogId: log.logId})" +
                            " WHERE trace.importOrder IS NOT NULL" +
                            " WITH trace ORDER BY trace.parentLogId, trace.importOrder LIMIT 1 RETURN trace } RETURN trace.traceId",
                    ).consume().profile()

                    val indexSeek = profile.find("NodeIndexSeek")
                    assertNotNull(indexSeek)
                    assertEquals(1L, indexSeek.records())
                    assertTrue(profile.totalDbHits() <= 10, "Expected a bounded index read, got ${profile.totalDbHits()} DB hits")
                    assertEquals(null, profile.find("Top"))

                    session.run(
                        "MATCH (trace:Trace {traceId: 't-0'}) UNWIND range(0, 1999) AS i" +
                            " CREATE (trace)-[:HAS_EVENT]->(:Event {eventId: 'e-' + i," +
                            " parentTraceId: 't-0', importOrder: i})",
                    ).consume()
                    session.run(
                        "CREATE INDEX event_parent_import_order IF NOT EXISTS FOR (event:Event)" +
                            " ON (event.parentTraceId, event.importOrder)",
                    ).consume()
                    session.run("CALL db.awaitIndexes()").consume()

                    val eventProfile = session.run(
                        "PROFILE WITH 't-0' AS traceId" +
                            " CALL (traceId) { MATCH (event:Event {parentTraceId: traceId})" +
                            " WHERE event.importOrder IS NOT NULL" +
                            " WITH event ORDER BY event.parentTraceId, event.importOrder" +
                            " LIMIT 1 RETURN event } RETURN event.eventId",
                    ).consume().profile()

                    val eventIndexSeek = eventProfile.find("NodeIndexSeek")
                    assertNotNull(eventIndexSeek)
                    assertEquals(1L, eventIndexSeek.records())
                    assertTrue(
                        eventProfile.totalDbHits() <= 15,
                        "Expected a bounded event index read, got ${eventProfile.totalDbHits()} DB hits",
                    )
                    assertEquals(null, eventProfile.find("Top"))
                }
            }
        }
    }

    private fun ProfiledPlan.find(operator: String): ProfiledPlan? =
        takeIf { operatorType().substringBefore('@') == operator } ?: children().firstNotNullOfOrNull { it.find(operator) }

    private fun ProfiledPlan.totalDbHits(): Long = dbHits() + children().sumOf { it.totalDbHits() }
}
