package com.processm.processminterpreter.infrastructure.persistence.neo4j.repository

import com.processm.processminterpreter.domain.log.Log
import com.processm.processminterpreter.application.log.LogNotFoundException
import com.processm.processminterpreter.application.ports.LogRepository
import com.processm.processminterpreter.application.ports.LogStatistics
import org.neo4j.driver.Driver
import java.time.LocalDateTime
import org.springframework.stereotype.Repository

/**
 * Neo4j adapter for the [LogRepository] port.
 *
 * Node shape shared with [com.processm.processminterpreter.infrastructure.xes.XESLoader]:
 * ```
 * (:Log {
 *   logId:        String,   // primary key, maps to Log.id
 *   name:         String,
 *   createdAt:    LocalDateTime,
 *   updatedAt:    LocalDateTime,
 *   classifiers:  String?   // JSON — Map<String, List<String>>
 *   extensions:   String?   // JSON — List<{name,prefix,uri}>
 *   traceGlobals: String?   // JSON — Map<String, Any>
 *   eventGlobals: String?   // JSON — Map<String, Any>
 *   <custom>:     Any       // everything else is flattened as a node property
 * })
 * ```
 *
 * Structural keys above are excluded when materializing `Log.customAttributes`.
 * Classifiers / extensions / globals are stored as JSON strings rather than
 * adjacent nodes because they are always read back wholesale and Neo4j cannot
 * store heterogeneous lists of maps directly as a property.
 */
@Repository
class Neo4jLogRepository(
    private val driver: Driver,
    private val dataStoreReadCache: Neo4jDataStoreReadCache,
) : LogRepository {

    override fun save(log: Log): Log {
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run(
                    Neo4jLogNodeWrite.MERGE,
                    Neo4jLogNodeWrite.parameters(log),
                ).consume()
            }
        }
        dataStoreReadCache.invalidateLog(log.id)
        return log
    }

    override fun findById(id: String): Log? =
        driver.session().use { session ->
            session.executeRead { tx ->
                val result = tx.run(
                    "MATCH (l:Log {logId: \$logId}) RETURN l",
                    mapOf("logId" to id),
                )
                if (result.hasNext()) Neo4jLogNodeMapper.toDomain(result.single().get("l").asNode()) else null
            }
        }

    override fun findAll(): List<Log> =
        driver.session().use { session ->
            session.executeRead { tx ->
                tx.run("MATCH (l:Log) RETURN l ORDER BY l.createdAt DESC")
                    .list { Neo4jLogNodeMapper.toDomain(it.get("l").asNode()) }
            }
        }

    override fun search(namePart: String): List<Log> {
        require(namePart.isNotBlank()) { "Search name cannot be blank" }
        return driver.session().use { session ->
            session.executeRead { tx ->
                tx.run(
                    "MATCH (l:Log) WHERE toLower(l.name) CONTAINS toLower(\$q) RETURN l",
                    mapOf("q" to namePart),
                ).list { Neo4jLogNodeMapper.toDomain(it.get("l").asNode()) }
            }
        }
    }

    override fun findByAttribute(key: String, value: Any): List<Log> {
        require(key.isNotBlank()) { "Attribute key cannot be blank" }
        return driver.session().use { session ->
            session.executeRead { tx ->
                tx.run(
                    "MATCH (l:Log) WHERE l[\$key] = \$value RETURN l",
                    mapOf("key" to key, "value" to value),
                ).list { Neo4jLogNodeMapper.toDomain(it.get("l").asNode()) }
            }
        }
    }

    override fun findCreatedAfter(date: LocalDateTime): List<Log> =
        driver.session().use { session ->
            session.executeRead { tx ->
                tx.run(
                    "MATCH (l:Log) WHERE l.createdAt > \$date RETURN l ORDER BY l.createdAt DESC",
                    mapOf("date" to date),
                ).list { Neo4jLogNodeMapper.toDomain(it.get("l").asNode()) }
            }
        }

    override fun findCreatedBetween(start: LocalDateTime, end: LocalDateTime): List<Log> {
        require(start.isBefore(end)) { "Start date must be before end date" }
        return driver.session().use { session ->
            session.executeRead { tx ->
                tx.run(
                    "MATCH (l:Log) WHERE l.createdAt >= \$start AND l.createdAt <= \$end " +
                        "RETURN l ORDER BY l.createdAt DESC",
                    mapOf("start" to start, "end" to end),
                ).list { Neo4jLogNodeMapper.toDomain(it.get("l").asNode()) }
            }
        }
    }

    override fun getStatistics(id: String): LogStatistics? =
        driver.session().use { session ->
            session.executeRead { tx ->
                val result = tx.run(
                    """
                    MATCH (l:Log {logId: ${'$'}logId})
                    OPTIONAL MATCH (l)-[:CONTAINS]->(t:Trace)
                    OPTIONAL MATCH (t)-[:HAS_EVENT]->(e:Event)
                    RETURN count(DISTINCT t) AS traceCount, count(DISTINCT e) AS eventCount,
                           count(l) AS logPresent
                    """.trimIndent(),
                    mapOf("logId" to id),
                )
                if (!result.hasNext()) return@executeRead null
                val rec = result.single()
                if (rec.get("logPresent").asLong() == 0L) null
                else LogStatistics(
                    traceCount = rec.get("traceCount").asLong(),
                    eventCount = rec.get("eventCount").asLong(),
                )
            }
        }

    override fun getStatisticsAll(): List<Pair<Log, LogStatistics>> =
        driver.session().use { session ->
            session.executeRead { tx ->
                tx.run(
                    """
                    MATCH (l:Log)
                    OPTIONAL MATCH (l)-[:CONTAINS]->(t:Trace)
                    OPTIONAL MATCH (t)-[:HAS_EVENT]->(e:Event)
                    RETURN l, count(DISTINCT t) AS traceCount, count(DISTINCT e) AS eventCount
                    ORDER BY l.createdAt DESC
                    """.trimIndent(),
                ).list { rec ->
                    val log = Neo4jLogNodeMapper.toDomain(rec.get("l").asNode())
                    val stats = LogStatistics(
                        traceCount = rec.get("traceCount").asLong(),
                        eventCount = rec.get("eventCount").asLong(),
                    )
                    log to stats
                }
            }
        }

    override fun getClassifiers(id: String): Map<String, List<String>> {
        val log = findById(id) ?: throw LogNotFoundException(id)
        return log.classifiers.associate { it.name to it.keys }
    }

    override fun update(log: Log): Log {
        if (!exists(log.id)) throw LogNotFoundException(log.id)
        // Use now() for updatedAt so callers can't freeze the timestamp.
        return save(log.copy(updatedAt = LocalDateTime.now()))
    }

    override fun delete(id: String): Boolean {
        if (!exists(id)) return false
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run("MATCH (l:Log {logId: \$logId}) DETACH DELETE l", mapOf("logId" to id))
                    .consume()
            }
        }
        dataStoreReadCache.invalidateLog(id)
        return true
    }

    override fun deleteWithData(id: String): Boolean {
        if (!exists(id)) return false
        driver.session().use { session ->
            session.executeWrite { tx ->
                tx.run(
                    """
                    MATCH (l:Log {logId: ${'$'}logId})
                    OPTIONAL MATCH (l)-[:CONTAINS]->(t:Trace)
                    OPTIONAL MATCH (t)-[:HAS_EVENT]->(e:Event)
                    DETACH DELETE l, t, e
                    """.trimIndent(),
                    mapOf("logId" to id),
                ).consume()
            }
        }
        dataStoreReadCache.invalidateLog(id)
        return true
    }

    override fun exists(id: String): Boolean =
        driver.session().use { session ->
            session.executeRead { tx ->
                tx.run(
                    "MATCH (l:Log {logId: \$logId}) RETURN count(l) > 0 AS present",
                    mapOf("logId" to id),
                ).single().get("present").asBoolean()
            }
        }

}
