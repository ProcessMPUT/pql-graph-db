package com.processm.processminterpreter.neo4j.query

import org.junit.jupiter.api.Test
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.summary.ProfiledPlan
import org.testcontainers.neo4j.Neo4jContainer
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class Neo4jEventNameTextIndexPlanTest {
    @Test
    fun `event-first contains query uses the activity text index`() {
        Neo4jContainer("neo4j:2026.07.1-community-ubi10").withAdminPassword("password").use { neo4j ->
            neo4j.start()
            GraphDatabase.driver(neo4j.boltUrl, AuthTokens.basic("neo4j", "password")).use { driver ->
                driver.session().use { session ->
                    session.run(
                        "CREATE (:DataStore {dataStoreId: 'store-1'})-[:CONTAINS_LOG]->" +
                            "(log:Log {logId: 'log-1'})-[:CONTAINS]->" +
                            "(trace:Trace {traceId: 'trace-1', parentLogId: 'log-1', importOrder: 0})" +
                            " WITH trace UNWIND range(0, 4999) AS i" +
                            " CREATE (trace)-[:HAS_EVENT]->(:Event {" +
                            "eventId: 'event-' + i, parentTraceId: 'trace-1', importOrder: i," +
                            "activity: CASE WHEN i = 4321 THEN 'rare-needle' ELSE 'common' END})",
                    ).consume()
                    session.run(
                        "CREATE TEXT INDEX event_activity_text IF NOT EXISTS" +
                            " FOR (event:Event) ON (event.activity)",
                    ).consume()
                    session.run("CALL db.awaitIndexes()").consume()

                    val profile = session.run(
                        "PROFILE MATCH (:DataStore {dataStoreId: 'store-1'})-[:CONTAINS_LOG]->(log:Log)" +
                            " CALL (log) { MATCH (event:Event) WHERE event.activity CONTAINS 'needle'" +
                            " MATCH (trace:Trace)-[:HAS_EVENT]->(event)" +
                            " WHERE trace.parentLogId = log.logId" +
                            " WITH trace, event ORDER BY event.activity, trace.importOrder, event.importOrder" +
                            " LIMIT 1 RETURN event.activity AS logOrder }" +
                            " WITH log, logOrder ORDER BY logOrder LIMIT 1" +
                            " CALL (log) { MATCH (event:Event) WHERE event.activity CONTAINS 'needle'" +
                            " MATCH (trace:Trace)-[:HAS_EVENT]->(event)" +
                            " WHERE trace.parentLogId = log.logId" +
                            " WITH trace, event ORDER BY trace.traceId, event.activity, event.importOrder" +
                            " WITH trace, collect(properties(event))[..20] AS events" +
                            " WITH trace, events ORDER BY trace.parentLogId, trace.importOrder LIMIT 10" +
                            " RETURN trace, events } RETURN trace.traceId, events",
                    ).consume().profile()

                    val indexScan = profile.find("NodeIndexContainsScan")
                    assertNotNull(indexScan, profile.toString())
                    assertTrue(indexScan.records() <= 1, "Expected only the selective match, got ${indexScan.records()}")
                }
            }
        }
    }

    private fun ProfiledPlan.find(operator: String): ProfiledPlan? =
        takeIf { operatorType().substringBefore('@') == operator } ?: children().firstNotNullOfOrNull { it.find(operator) }
}
