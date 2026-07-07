package com.processm.processminterpreter.xes

import com.processm.processminterpreter.xes.model.Log
import java.time.LocalDateTime

interface LogRepository {
    fun save(log: Log): Log
    fun findById(id: String): Log?
    fun findAll(): List<Log>
    fun search(namePart: String): List<Log>
    fun findByAttribute(key: String, value: Any): List<Log>
    fun findCreatedAfter(date: LocalDateTime): List<Log>
    fun findCreatedBetween(start: LocalDateTime, end: LocalDateTime): List<Log>
    fun getStatistics(id: String): LogStatistics?
    fun getStatisticsAll(): List<Pair<Log, LogStatistics>>
    fun update(log: Log): Log
    fun delete(id: String): Boolean
    fun deleteWithData(id: String): Boolean
    fun exists(id: String): Boolean
}

data class LogStatistics(
    val traceCount: Long,
    val eventCount: Long,
)
