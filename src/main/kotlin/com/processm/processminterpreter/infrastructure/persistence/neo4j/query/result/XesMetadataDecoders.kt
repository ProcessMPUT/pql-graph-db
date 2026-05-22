package com.processm.processminterpreter.infrastructure.persistence.neo4j.query.result

import com.processm.processminterpreter.domain.log.AttributeScope
import com.processm.processminterpreter.domain.log.Classifier
import com.processm.processminterpreter.domain.log.Extension
import com.processm.processminterpreter.domain.log.GlobalAttribute
import com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.metadata.XesLogMetadataCodec

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
