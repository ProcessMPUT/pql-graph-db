package com.processm.processminterpreter.xes.io

import com.processm.processminterpreter.xes.io.XesWriteOptions
import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import com.processm.processminterpreter.pql.catalog.StandardAttributeCatalog
import org.springframework.stereotype.Component
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

/**
 * Immutable [XesLog] to XES XML serializer.
 *
 * This class owns XES document structure. Low-level XML attribute emission,
 * escaping and primitive value formatting live in [XesXmlEmitter].
 */
@Component
class OpenXesWriter {
    fun write(logs: List<XesLog>, output: OutputStream, options: XesWriteOptions = XesWriteOptions()) {
        val sink: OutputStream = if (options.compress) GZIPOutputStream(output) else output
        sink.use { stream ->
            val writer = OutputStreamWriter(stream, StandardCharsets.UTF_8)
            val xml = XesXmlEmitter(writer)

            writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
            if (logs.isEmpty()) {
                writeEmptyLog(writer, xml, options)
            } else {
                logs.forEach { writeLog(writer, xml, it, options) }
            }
            writer.flush()
        }
    }

    private fun writeEmptyLog(
        writer: OutputStreamWriter,
        xml: XesXmlEmitter,
        options: XesWriteOptions,
    ) {
        writer.write(LOG_OPEN)
        xml.defaultExtensions()
        xml.string(StandardAttributeCatalog.CONCEPT_NAME, options.logName, indent = "\t")
        writer.write("</log>\n")
    }

    private fun writeLog(
        writer: OutputStreamWriter,
        xml: XesXmlEmitter,
        log: XesLog,
        options: XesWriteOptions,
    ) {
        val keys = StandardKeys.from(log)
        writer.write(LOG_OPEN)

        if (log.extensions.isNotEmpty()) {
            log.extensions.forEach { xml.extension(it) }
        } else {
            xml.defaultExtensions()
        }

        if (log.traceGlobals.isNotEmpty()) xml.globalsBlock("trace", log.traceGlobals)
        if (log.eventGlobals.isNotEmpty()) xml.globalsBlock("event", log.eventGlobals)
        log.classifiers.forEach { xml.classifier(it) }

        val logName = log.conceptName ?: options.logName
        xml.string(keys.conceptName, logName, indent = "\t")
        log.identityId?.let { xml.id(keys.identityId, it, indent = "\t") }
        log.lifecycleModel?.let {
            xml.string(keys.lifecycleModel, it, indent = "\t")
        }
        xml.customAttributes(log.customAttributes, indent = "\t")

        log.traces.forEach { writeTrace(writer, xml, keys, it) }

        writer.write("</log>\n")
    }

    private fun writeTrace(
        writer: OutputStreamWriter,
        xml: XesXmlEmitter,
        keys: StandardKeys,
        trace: XesTrace,
    ) {
        writer.write("\t<trace>\n")
        trace.conceptName?.let {
            xml.string(keys.conceptName, it, indent = "\t\t")
        }
        trace.identityId?.let {
            xml.id(keys.identityId, it, indent = "\t\t")
        }
        trace.costTotal?.let {
            xml.float(keys.costTotal, it, indent = "\t\t")
        }
        trace.costCurrency?.let {
            xml.string(keys.costCurrency, it, indent = "\t\t")
        }
        xml.customAttributes(trace.customAttributes, indent = "\t\t")

        trace.events.forEach { writeEvent(writer, xml, keys, it) }
        repeat(trace.nullEventCount) { writer.write("\t\t<event/>\n") }

        writer.write("\t</trace>\n")
    }

    private fun writeEvent(
        writer: OutputStreamWriter,
        xml: XesXmlEmitter,
        keys: StandardKeys,
        event: XesEvent,
    ) {
        writer.write("\t\t<event>\n")
        event.conceptName?.let {
            xml.string(keys.conceptName, it, indent = "\t\t\t")
        }
        event.conceptInstance?.let {
            xml.string(keys.conceptInstance, it, indent = "\t\t\t")
        }
        event.identityId?.let {
            xml.id(keys.identityId, it, indent = "\t\t\t")
        }
        event.timeTimestamp?.let {
            xml.date(keys.timeTimestamp, it, indent = "\t\t\t")
        }
        event.lifecycleTransition?.let {
            xml.string(keys.lifecycleTransition, it, indent = "\t\t\t")
        }
        event.lifecycleState?.let {
            xml.string(keys.lifecycleState, it, indent = "\t\t\t")
        }
        event.orgResource?.let {
            xml.string(keys.orgResource, it, indent = "\t\t\t")
        }
        event.orgRole?.let {
            xml.string(keys.orgRole, it, indent = "\t\t\t")
        }
        event.orgGroup?.let {
            xml.string(keys.orgGroup, it, indent = "\t\t\t")
        }
        event.costTotal?.let {
            xml.float(keys.costTotal, it, indent = "\t\t\t")
        }
        event.costCurrency?.let {
            xml.string(keys.costCurrency, it, indent = "\t\t\t")
        }
        xml.customAttributes(event.customAttributes, indent = "\t\t\t")
        writer.write("\t\t</event>\n")
    }

    private data class StandardKeys(
        val conceptName: String,
        val conceptInstance: String,
        val identityId: String,
        val lifecycleModel: String,
        val lifecycleTransition: String,
        val lifecycleState: String,
        val timeTimestamp: String,
        val orgResource: String,
        val orgRole: String,
        val orgGroup: String,
        val costTotal: String,
        val costCurrency: String,
    ) {
        companion object {
            fun from(log: XesLog): StandardKeys {
                fun prefix(extensionName: String, default: String): String =
                    log.extensions.firstOrNull { it.name == extensionName }?.prefix?.takeIf { it.isNotBlank() } ?: default

                val concept = prefix("Concept", "concept")
                val identity = prefix("Identity", "identity")
                val lifecycle = prefix("Lifecycle", "lifecycle")
                val time = prefix("Time", "time")
                val org = prefix("Organizational", "org")
                val cost = prefix("Cost", "cost")

                return StandardKeys(
                    conceptName = "$concept:name",
                    conceptInstance = "$concept:instance",
                    identityId = "$identity:id",
                    lifecycleModel = "$lifecycle:model",
                    lifecycleTransition = "$lifecycle:transition",
                    lifecycleState = "$lifecycle:state",
                    timeTimestamp = "$time:timestamp",
                    orgResource = "$org:resource",
                    orgRole = "$org:role",
                    orgGroup = "$org:group",
                    costTotal = "$cost:total",
                    costCurrency = "$cost:currency",
                )
            }
        }
    }

    private companion object {
        const val LOG_OPEN =
            "<log xes.version=\"1.0\" xes.features=\"nested-attributes\" " +
                "xmlns=\"http://www.xes-standard.org/\">\n"
    }
}
