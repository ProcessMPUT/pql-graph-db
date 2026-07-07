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
internal fun deleteLogSubtreesBatched(session: Session, logMatch: String, params: Map<String, Any?>) {
    session.run(
        "$logMatch-[:CONTAINS]->(:Trace)-[:HAS_EVENT]->(e:Event)" +
            " CALL (e) { DETACH DELETE e } IN TRANSACTIONS OF 20000 ROWS",
        params,
    ).consume()
    session.run(
        "$logMatch-[:CONTAINS]->(t:Trace)" +
            " CALL (t) { DETACH DELETE t } IN TRANSACTIONS OF 20000 ROWS",
        params,
    ).consume()
}
