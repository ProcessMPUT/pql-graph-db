package com.processm.processminterpreter.benchmark

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** One immutable, independently prepared task from the protocol-27 study schedule. */
data class StudyJob(
    val id: String,
    val kind: String,
    val phase: String,
    val planSha256: String,
    val planStatus: String,
    val datasetNames: List<String>,
    val queryLabels: List<String>,
    val seed: Long,
    val warmups: Int,
    val pairs: Int,
    val importPairs: Int,
    val globalWarmupRounds: Int,
    val resourceWindowSeconds: Int,
    val resourceMinimumSamples: Int,
    val queryDefinitionsSha256: String,
    val datasetDefinitionsSha256: String,
    val protocolVersion: Int = 27,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val resourceProbe: StudyResourceProbe? = null,
) {
    @get:JsonIgnore val collectsLatency: Boolean get() = kind in setOf("latency", "pilot")
    @get:JsonIgnore val collectsResources: Boolean get() = kind in setOf("resources", "pilot")
    @get:JsonIgnore val checksRoundtrip: Boolean get() = phase in setOf("coverage", "pilot")

    fun collectsResourcesFor(dataset: String, query: String): Boolean = kind == "resources" ||
        (kind == "pilot" && resourceProbe?.let { it.dataset == dataset && it.query == query } == true)

    fun validate(config: BenchmarkConfig) {
        require(protocolVersion == 27 && id.matches(Regex("[A-Za-z0-9_-]+")))
        require(planSha256.matches(Regex("[a-f0-9]{64}")))
        require(kind in setOf("latency", "resources", "imports", "compatibility", "pilot"))
        require(planStatus == "frozen" || kind == "pilot") { "Only a pilot may use a draft study plan" }
        require(kind != "latency" || pairs == 30) { "Final latency blocks require exactly 30 pairs (supervisor requirement)" }
        require(warmups >= 0 && pairs > 0 && pairs % 2 == 0 && importPairs > 0 && globalWarmupRounds >= 0)
        require(resourceWindowSeconds > 0 && resourceMinimumSamples > 0)
        require(datasetNames.distinct() == datasetNames && queryLabels.distinct() == queryLabels)
        if (kind != "compatibility") {
            require(datasetNames.isNotEmpty() && config.datasets.map { it.name }.containsAll(datasetNames))
            require(config.queries.map { it.label }.containsAll(queryLabels))
            if (kind != "imports") require(queryLabels.isNotEmpty())
        }
        require((kind == "pilot") == (resourceProbe != null)) { "Only a pilot must declare one resource probe" }
        resourceProbe?.let { probe ->
            val dataset = config.datasets.singleOrNull { it.name == probe.dataset }
            val query = config.queries.singleOrNull { it.label == probe.query }
            require(probe.dataset in datasetNames && probe.query in queryLabels && dataset != null &&
                query?.isMeasuredFor(dataset.name, dataset.series) == true) {
                "Pilot resource probe must be one of its planned measured cells"
            }
        }
        require(queryDefinitionsSha256 == resourceSha256("benchmark-queries.json")) { "Changed PQL definitions" }
        require(datasetDefinitionsSha256 == resourceSha256("benchmark-datasets.json")) { "Changed data definitions" }
    }

    companion object {
        fun load(path: Path): StudyJob = jacksonObjectMapper().readValue(Files.readString(path), StudyJob::class.java)

        fun resourceSha256(name: String): String = MessageDigest.getInstance("SHA-256")
            .digest(requireNotNull(StudyJob::class.java.classLoader.getResourceAsStream(name)).use { it.readBytes() })
            .joinToString("") { "%02x".format(it) }
    }
}
