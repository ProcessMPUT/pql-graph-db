package com.processm.processminterpreter.domain.pql.interpreter

import com.processm.processminterpreter.infrastructure.persistence.neo4j.xes.Neo4jXesLogWriter
import com.processm.processminterpreter.infrastructure.xes.OpenXesReader
import com.processm.processminterpreter.infrastructure.xes.XESLoader
import com.processm.processminterpreter.infrastructure.xes.XESParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

@Tag("Verification")
class HospitalLogVerificationTests : BaseInterpreterTest() {
    private lateinit var xesLoader: XESLoader

    @BeforeEach
    fun prepareData() {
        try {
            clearDatabase()

            val xesParser = XESParser()
            xesLoader = XESLoader(OpenXesReader(xesParser), Neo4jXesLogWriter(driver))

            val logFile = File("src/main/resources/logs/Hospital_log.xes")
            if (!logFile.exists()) {
                throw RuntimeException("Hospital_log.xes not found at ${logFile.absolutePath}")
            }
            println("Loading XES file: ${logFile.absolutePath}")
            val importResult = xesLoader.loadXESFile(logFile.inputStream(), "hospital-log")
            attachLogToInterpreterDataStore(importResult.logId ?: "hospital-log")
            println("XES file loaded successfully")
        } catch (e: Throwable) {
            println("ERROR in prepareData: ${e.message}")
            e.printStackTrace()
            throw RuntimeException(e)
        }
    }

    @Test
    fun verifyHospitalLogQueries() {
        val queries =
            listOf(
                "Basic Stats" to "select count(t:id), count(e:id)",
                "Activities" to "select e:concept:name, count(e:id) group by e:concept:name order by count(e:id) desc limit 5",
                "Resources" to "select e:org:group, count(e:id) group by e:org:group",
                "Hoisting" to "select ^e:concept:name, count(e:concept:name) group by ^e:concept:name",
            )

        println("\n=== LOCAL HOSPITAL LOG VERIFICATION ===")

        queries.forEach { (desc, query) ->
            println("\n--- $desc ---")
            println("Query: $query")
            try {
                val result = executeDataStoreQuery(query)
                if (result.success) {
                    println("Status: SUCCESS")
                    println("Rows: ${result.resultCount}")
                    println("Data (first 5 rows):")
                    result.results.take(5).forEach { println(it) }
                } else {
                    println("Status: FAIL")
                    println("Error: ${result.error}")
                }
            } catch (e: Exception) {
                println("Status: ERROR")
                println("Exception: ${e.message}")
            }
        }
        println("\n=======================================")
    }
}


