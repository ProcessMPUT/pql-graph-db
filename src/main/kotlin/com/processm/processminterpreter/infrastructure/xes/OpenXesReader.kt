package com.processm.processminterpreter.infrastructure.xes

import com.processm.processminterpreter.domain.log.xes.XesLog
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
