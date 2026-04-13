package com.processm.processminterpreter.service

import com.processm.processminterpreter.pql.CypherQuery
import com.processm.processminterpreter.pql.PQLTranslator
import com.processm.processminterpreter.xes.XESWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyMap
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.neo4j.driver.Driver
import org.neo4j.driver.Record
import org.neo4j.driver.Result
import org.neo4j.driver.Session
import java.util.function.Function

class PQLQueryServiceTest {
    @Mock
    private lateinit var pqlTranslator: PQLTranslator

    @Mock
    private lateinit var neo4jDriver: Driver

    @Mock
    private lateinit var xesWriter: XESWriter

    @Mock
    private lateinit var session: Session

    @Mock
    private lateinit var result: Result

    private lateinit var pqlQueryService: PQLQueryService

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        `when`(neo4jDriver.session()).thenReturn(session)
        // Default: classifier query returns no results
        val emptyResult = Mockito.mock(Result::class.java)
        `when`(emptyResult.hasNext()).thenReturn(false)
        `when`(session.run(Mockito.contains("log.classifiers"), anyMap<String, Any>())).thenReturn(emptyResult)
        pqlQueryService = PQLQueryService(pqlTranslator, neo4jDriver, xesWriter)
    }

    @Test
    fun `executePQLQuery should translate and execute query`() {
        // Given
        val pqlQuery = "select * from event"
        val cypherQueryString = "MATCH (n) RETURN n"
        val cypherQuery = CypherQuery(cypherQueryString, emptyMap())

        `when`(pqlTranslator.translateToCypher(pqlQuery, null)).thenReturn(cypherQuery)
        `when`(session.run(eq(cypherQueryString), anyMap<String, Any>())).thenReturn(result)
        // Use raw type or wildcard to avoid type inference issues with Mockito's any()
        `when`(result.list(any<Function<Record, Map<String, Any?>>>())).thenReturn(emptyList())

        // When
        val result = pqlQueryService.executePQLQuery(pqlQuery)

        // Then
        assertTrue(result.success)
        assertEquals(pqlQuery, result.query)
        assertEquals(cypherQueryString, result.cypherQuery)
        verify(pqlTranslator).translateToCypher(pqlQuery, null)
        verify(session).run(eq(cypherQueryString), anyMap<String, Any>())
    }

    @Test
    fun `executePQLQuery should handle errors`() {
        // Given
        val pqlQuery = "invalid query"
        val errorMessage = "Syntax error"

        `when`(pqlTranslator.translateToCypher(anyString(), any(), anyMap(), any())).thenThrow(IllegalArgumentException(errorMessage))

        // When
        val result = pqlQueryService.executePQLQuery(pqlQuery)

        // Then
        assertFalse(result.success)
        assertEquals(errorMessage, result.error)
        verify(session, never()).run(anyString(), anyMap())
    }

    @Test
    fun `validatePQLQuery should return valid result for correct query`() {
        // Given
        val pqlQuery = "select * from event"
        val cypherQuery = CypherQuery("MATCH (n) RETURN n", emptyMap())

        `when`(pqlTranslator.translateToCypher(pqlQuery, null)).thenReturn(cypherQuery)

        // When
        val result = pqlQueryService.validatePQLQuery(pqlQuery)

        // Then
        assertTrue(result.valid)
        assertEquals(cypherQuery.query, result.cypherQuery)
    }

    @Test
    fun `validatePQLQuery should return invalid result for incorrect query`() {
        // Given
        val pqlQuery = "invalid query"

        `when`(pqlTranslator.translateToCypher(anyString(), any(), anyMap(), any())).thenThrow(IllegalArgumentException("Error"))

        // When
        val result = pqlQueryService.validatePQLQuery(pqlQuery)

        // Then
        assertFalse(result.valid)
        assertNotNull(result.error)
    }

    @Test
    fun `executePQLQueryAsXES should write XES output`() {
        // Given
        val pqlQuery = "select * from event"
        val cypherQuery = CypherQuery("MATCH (n) RETURN n", emptyMap())

        `when`(pqlTranslator.translateToCypher(pqlQuery, null)).thenReturn(cypherQuery)
        `when`(session.run(eq(cypherQuery.query), anyMap<String, Any>())).thenReturn(result)
        `when`(result.list(any<Function<Record, Map<String, Any?>>>())).thenReturn(emptyList())

        // When
        pqlQueryService.executePQLQueryAsXES(pqlQuery)

        // Then
        verify(xesWriter).writeXES(anyList(), anyObject(), eq(false), anyString())
    }

    private fun <T> anyObject(): T {
        any<T>()
        return null as T
    }
}
