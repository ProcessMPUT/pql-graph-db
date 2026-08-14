package com.processm.processminterpreter.neo4j.repository

import com.processm.processminterpreter.xes.model.Log
import com.processm.processminterpreter.xes.LogNotFoundException
import com.processm.processminterpreter.xes.LogRepository
import com.processm.processminterpreter.xes.LogStatistics
import com.processm.processminterpreter.neo4j.xes.schema.Neo4jXesCustomAttributeCodec
import com.processm.processminterpreter.pql.catalog.Scope
import org.neo4j.driver.Driver
import java.time.LocalDateTime
import org.springframework.stereotype.Repository

/**
 * Neo4j adapter for the [LogRepository] port.
 *
 * Node shape shared with [com.processm.processminterpreter.xes.io.XESLoader]:
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
        val physicalKey = Neo4jXesCustomAttributeCodec.physicalName(Scope.LOG, key)
        return driver.session().use { session ->
            session.executeRead { tx ->
                tx.run(
                    "MATCH (l:Log) WHERE l[\$key] = \$value RETURN l",
                    mapOf("key" to physicalKey, "value" to value),
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

    // COUNT {} subqueries instead of OPTIONAL MATCH + count(DISTINCT ...): the
    // expanded pattern produces one row per event and deduplication has to hold
    // the distinct sets in memory, which is measurable on 100k+-event logs.
    override fun getStatistics(id: String): LogStatistics? =
        driver.session().use { session ->
            session.executeRead { tx ->
                val result = tx.run(
                    """
                    MATCH (l:Log {logId: ${'$'}logId})
                    RETURN COUNT { (l)-[:CONTAINS]->(:Trace) } AS traceCount,
                           COUNT { (l)-[:CONTAINS]->(:Trace)-[:HAS_EVENT]->(:Event) } AS eventCount
                    """.trimIndent(),
                    mapOf("logId" to id),
                )
                if (!result.hasNext()) return@executeRead null
                val rec = result.single()
                LogStatistics(
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
                    RETURN l,
                           COUNT { (l)-[:CONTAINS]->(:Trace) } AS traceCount,
                           COUNT { (l)-[:CONTAINS]->(:Trace)-[:HAS_EVENT]->(:Event) } AS eventCount
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

    override fun update(log: Log): Log {
        // MATCH makes existence checking and replacement one atomic write. All
        // non-managed properties are the previous XES attribute set and are
        // cleared by UPDATE before the replacement set is applied.
        val updated = log.copy(updatedAt = LocalDateTime.now())
        return driver.session().use { session ->
            session.executeWrite { tx ->
                val result = tx.run(Neo4jLogNodeWrite.UPDATE, Neo4jLogNodeWrite.parameters(updated))
                if (!result.hasNext()) throw LogNotFoundException(log.id)
                Neo4jLogNodeMapper.toDomain(result.single().get("log").asNode())
            }
        }
    }

    // Deleting only the log node used to leave its traces/events orphaned and
    // permanently unreachable (a trace has no other parent), so both variants
    // clean up the whole subtree; they differ only in intent at the call site.
    override fun delete(id: String): Boolean = deleteWithData(id)

    override fun deleteWithData(id: String): Boolean {
        if (!exists(id)) return false
        driver.session().use { session ->
            val params = mapOf<String, Any?>("logId" to id)
            deleteLogSubtreesBatched(session, "MATCH (l:Log {logId: \$logId})", params)
            session.run("MATCH (l:Log {logId: \$logId}) DETACH DELETE l", params).consume()
        }
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
