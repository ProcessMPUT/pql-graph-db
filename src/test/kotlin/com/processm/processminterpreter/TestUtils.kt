package com.processm.processminterpreter

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object TestUtils {
    fun generateSyntheticLog(traceCount: Int, eventsPerTrace: Int): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
        sb.append("<log xes.version=\"1.0\" xes.features=\"nested-attributes\" xmlns=\"http://www.xes-standard.org/\">\n")
        sb.append("    <extension name=\"Concept\" prefix=\"concept\" uri=\"http://www.xes-standard.org/concept.xesext\"/>\n")
        sb.append("    <extension name=\"Time\" prefix=\"time\" uri=\"http://www.xes-standard.org/time.xesext\"/>\n")
        sb.append("    <string key=\"concept:name\" value=\"Synthetic Benchmark Log\"/>\n")

        val baseTime = LocalDateTime.of(2023, 1, 1, 10, 0)
        
        for (i in 1..traceCount) {
            sb.append("    <trace>\n")
            sb.append("        <string key=\"concept:name\" value=\"Case $i\"/>\n")
            
            for (j in 1..eventsPerTrace) {
                val timestamp = baseTime.plusMinutes((i * eventsPerTrace + j).toLong())
                val activity = listOf("A", "B", "C", "D", "E").random()
                val resource = listOf("User1", "User2", "User3").random()
                val cost = (10..100).random().toDouble()
                
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
                val timestampStr = timestamp.format(formatter) + "Z"
                
                sb.append("        <event>\n")
                sb.append("            <string key=\"concept:name\" value=\"$activity\"/>\n")
                sb.append("            <date key=\"time:timestamp\" value=\"$timestampStr\"/>\n")
                sb.append("            <string key=\"org:resource\" value=\"$resource\"/>\n")
                sb.append("            <float key=\"cost:total\" value=\"$cost\"/>\n")
                sb.append("        </event>\n")
            }
            sb.append("    </trace>\n")
        }
        
        sb.append("</log>")
        return sb.toString()
    }
}
