package com.processm.processminterpreter.neo4j.repository

import org.neo4j.driver.Session

/**
 * Deletes the trace/event subtree under the logs bound by [logMatch] in
 * memory-bounded batches.
 *
 * A single `DETACH DELETE` over a whole subtree materializes every deleted
 * entity in one transaction and blows `dbms.memory.transaction.total.max` on
 * large logs (observed: 561k-event Road Traffic Fine Management log vs the
 * 4.2 GiB default). `CALL ... IN TRANSACTIONS` splits the work into implicit
 * transactions, so it must run on a plain [Session.run] (auto-commit), never
 * inside `executeWrite`.
 *
 * Batched deletion is not atomic: a failure mid-way leaves the log partially
 * deleted. Deletion is resumable — re-running removes the remainder — which
 * matches how callers treat delete (idempotent cleanup), unlike imports.
 */
internal fun deleteLogSubtreesBatched(session: Session, logMatch: String, params: Map<String, Any?>): Long {
    val eventsDeleted = session.run(
        "$logMatch-[:CONTAINS]->(:Trace)-[:HAS_EVENT]->(e:Event)" +
            " CALL (e) { DETACH DELETE e } IN TRANSACTIONS OF $SUBTREE_DELETE_BATCH_SIZE ROWS",
        params,
    ).consume().counters().nodesDeleted().toLong()
    val tracesDeleted = session.run(
        "$logMatch-[:CONTAINS]->(t:Trace)" +
            " CALL (t) { DETACH DELETE t } IN TRANSACTIONS OF $SUBTREE_DELETE_BATCH_SIZE ROWS",
        params,
    ).consume().counters().nodesDeleted().toLong()
    return eventsDeleted + tracesDeleted
}

internal fun deleteTraceSubtreesBatched(session: Session, traceIds: List<String>): Long {
    val params = mapOf<String, Any?>("traceIds" to traceIds)
    val eventsDeleted = session.run(
        "UNWIND ${'$'}traceIds AS traceId MATCH (trace:Trace {traceId: traceId})-[:HAS_EVENT]->(event:Event)" +
            " WITH DISTINCT event CALL (event) { DETACH DELETE event }" +
            " IN TRANSACTIONS OF $SUBTREE_DELETE_BATCH_SIZE ROWS",
        params,
    ).consume().counters().nodesDeleted().toLong()
    val tracesDeleted = session.run(
        "UNWIND ${'$'}traceIds AS traceId MATCH (trace:Trace {traceId: traceId}) DETACH DELETE trace",
        params,
    ).consume().counters().nodesDeleted().toLong()
    return eventsDeleted + tracesDeleted
}

internal fun deleteEventsBatched(session: Session, eventIds: List<String>): Long =
    session.run(
        "UNWIND ${'$'}eventIds AS eventId MATCH (event:Event {eventId: eventId}) DETACH DELETE event",
        mapOf("eventIds" to eventIds),
    ).consume().counters().nodesDeleted().toLong()

/**
 * Keeps deletion below the 2496 MiB Neo4j benchmark cgroup budget even after
 * importing one million richly attributed events. Cleanup is outside every
 * measured benchmark interval, so the smaller transaction trades only cleanup
 * throughput for bounded peak memory.
 */
private const val SUBTREE_DELETE_BATCH_SIZE = 5_000
