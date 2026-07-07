package com.processm.processminterpreter.xes.io

import com.processm.processminterpreter.xes.model.XesLog
import org.springframework.stereotype.Component
import java.io.InputStream

/**
 * XES XML -> immutable [XesLog] reader.
 */
@Component
class OpenXesReader(
    private val parser: XESParser = XESParser(),
) {

    fun read(input: InputStream): List<XesLog> = listOf(parser.parseXesLog(input))
}
