package com.processm.processminterpreter.pql

import java.io.OutputStream

/**
 * An already-executed XES export whose XML serialization is deferred: the query
 * ran (and could still fail) when this object was created, [write] only turns
 * the materialized logs into XES bytes on the given stream.
 */
class PreparedXesExport(
    val result: ExportResult,
    val write: (OutputStream) -> Unit,
)
