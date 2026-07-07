package com.processm.processminterpreter.neo4j.query.result

import com.processm.processminterpreter.xes.model.AttributeScope
import com.processm.processminterpreter.xes.model.Classifier
import com.processm.processminterpreter.xes.model.Extension
import com.processm.processminterpreter.xes.model.GlobalAttribute
import com.processm.processminterpreter.neo4j.xes.metadata.XesLogMetadataCodec

internal fun parseClassifiers(json: String?): List<Classifier> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching { XesLogMetadataCodec.deserializeClassifiers(json) }.getOrElse { emptyList() }
}

internal fun parseExtensions(json: String?): List<Extension> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching { XesLogMetadataCodec.deserializeExtensions(json) }.getOrElse { emptyList() }
}

internal fun parseGlobals(json: String?, scope: AttributeScope): List<GlobalAttribute> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching { XesLogMetadataCodec.deserializeGlobals(json, scope) }.getOrElse { emptyList() }
}
