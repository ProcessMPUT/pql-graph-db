package com.processm.processminterpreter.processm.compat

import com.processm.processminterpreter.processm.ProcessMUploadResponse
import com.processm.processminterpreter.processm.json.QueryJsonProjection
import com.processm.processminterpreter.processm.RemoteProcessMDataStore
import com.processm.processminterpreter.processm.RemoteProcessMGateway
import com.processm.processminterpreter.processm.RemoteQueryExecutionResult
import com.processm.processminterpreter.pql.ExecutePqlQueryRequest
import com.processm.processminterpreter.pql.PqlQueryService
import com.processm.processminterpreter.pql.QueryResult
import com.processm.processminterpreter.processm.json.ProcessMXesJsonFormatter
import com.processm.processminterpreter.xes.model.XesEvent
import com.processm.processminterpreter.xes.model.XesLog
import com.processm.processminterpreter.xes.model.XesTrace
import com.processm.processminterpreter.pql.catalog.SourceLocation
import com.processm.processminterpreter.pql.common.HierarchicalLimits
import com.processm.processminterpreter.pql.error.PQLSyntaxException
import com.processm.processminterpreter.pql.error.Problem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class VerificationServiceTest {

    @Test
    fun `matching known PQL failures are treated as compatible`() {
        val executeUseCase = Mockito.mock(PqlQueryService::class.java)
        Mockito.`when`(executeUseCase.execute(anyArg())).thenThrow(
            PQLSyntaxException(
                Problem.PositiveIntegerRequired,
                SourceLocation(1, 6),
                "PositiveIntegerRequired - e:0",
            ),
        )
        val remote = FakeRemoteProcessMGateway(
            RemoteQueryExecutionResult(
                success = false,
                message = "Line 1 position 6: A value of the limit must be a positive integer, event:0.0 given.",
            ),
        )

        val result = useCase(executeUseCase, remote).verify(
            VerifyPqlQueryRequest(query = "limit e:0"),
        )

        assertTrue(result.match, result.details)
        assertFalse(result.localSuccess)
        assertFalse(result.remoteSuccess)
        assertTrue(result.details.contains("positive integer required"), result.details)
    }

    @Test
    fun `remote classifier stream abort is treated as invalid classifier failure`() {
        val executeUseCase = Mockito.mock(PqlQueryService::class.java)
        Mockito.`when`(executeUseCase.execute(anyArg())).thenThrow(
            PQLSyntaxException(
                Problem.InvalidUseOfClassifiers,
                SourceLocation(1, 9),
                "Classifier 'Resource' not found",
            ),
        )
        val remote = FakeRemoteProcessMGateway(
            RemoteQueryExecutionResult(
                success = false,
                message = "Exception: Unexpected end-of-input: expected close marker for Array",
            ),
        )

        val result = useCase(executeUseCase, remote).verify(
            VerifyPqlQueryRequest(query = "group by c:Resource"),
        )

        assertTrue(result.match, result.details)
        assertTrue(result.details.contains("invalid classifier"), result.details)
    }

    @Test
    fun `unknown failures are not treated as compatible just because both sides failed`() {
        val executeUseCase = Mockito.mock(PqlQueryService::class.java)
        Mockito.`when`(executeUseCase.execute(anyArg())).thenThrow(RuntimeException("local boom"))
        val remote = FakeRemoteProcessMGateway(
            RemoteQueryExecutionResult(success = false, message = "remote boom"),
        )

        val result = useCase(executeUseCase, remote).verify(
            VerifyPqlQueryRequest(query = "select broken"),
        )

        assertFalse(result.match, result.details)
        assertTrue(result.details.contains("failed differently"), result.details)
    }

    @Test
    fun `hoisted variant window mismatch is classified as nondeterministic when remote is in widened local result`() {
        val limitedLog = XesLog(conceptName = "limited")
        val uncappedLog = XesLog(
            conceptName = "uncapped",
            traces = listOf(domainTrace("A"), domainTrace("B"), domainTrace("C")),
        )
        val executeUseCase = Mockito.mock(PqlQueryService::class.java)
        val capturedRequests = mutableListOf<ExecutePqlQueryRequest>()
        Mockito.`when`(executeUseCase.execute(anyArg())).thenAnswer { invocation ->
            capturedRequests += invocation.getArgument<ExecutePqlQueryRequest>(0)
            QueryResult(logs = listOf(if (capturedRequests.size == 1) limitedLog else uncappedLog))
        }

        val localLimited = xesLogWithTraces(trace("A"), trace("C"))
        val remoteResult = xesLogWithTraces(trace("B"), trace("C"))
        var formatterCalls = 0
        val formatter = object : ProcessMXesJsonFormatter() {
            override fun formatAsXesJson(result: QueryJsonProjection): List<Map<String, Any?>> {
                formatterCalls++
                return when (result.logs.first().conceptName) {
                    "limited" -> localLimited
                    "uncapped" -> error("uncapped fallback should use domain fingerprints, not JSON formatting")
                    else -> emptyList()
                }
            }
        }
        val remote = FakeRemoteProcessMGateway(
            RemoteQueryExecutionResult(success = true, message = "OK", resultCount = 1, results = remoteResult),
        )

        val result = VerificationService(executeUseCase, remote, formatter).verify(
            VerifyPqlQueryRequest(
                query = "group by ^e:name order by name",
                includeTraces = true,
                includeEvents = true,
                defaultLimits = HierarchicalLimits(log = 10, trace = 30, event = 90),
            ),
        )

        assertFalse(result.match, result.details)
        assertEquals(ComparisonStatus.NONDETERMINISTIC_MATCH.name, result.comparisonStatus)
        assertEquals(HierarchicalLimits(log = 10, trace = 30, event = 90), capturedRequests[0].defaultLimits)
        assertEquals(HierarchicalLimits(log = 10, trace = 60, event = 90), capturedRequests[1].defaultLimits)
        assertEquals(2, capturedRequests.size)
        assertEquals(1, formatterCalls)
        assertTrue(result.details.contains("NONDETERMINISTIC_MATCH"), result.details)
    }

    private fun useCase(
        executeUseCase: PqlQueryService,
        remote: RemoteProcessMGateway,
    ): VerificationService =
        VerificationService(
            pqlQueryService = executeUseCase,
            remoteProcessM = remote,
            formatter = object : ProcessMXesJsonFormatter() {
                override fun formatAsXesJson(result: QueryJsonProjection): List<Map<String, Any?>> = emptyList()
            },
        )

    private class FakeRemoteProcessMGateway(
        private val result: RemoteQueryExecutionResult,
    ) : RemoteProcessMGateway {
        override fun executeQuery(
            query: String,
            remoteDataStoreId: String?,
            includeTraces: Boolean,
            includeEvents: Boolean,
        ): RemoteQueryExecutionResult = result

        override fun uploadLog(
            bytes: ByteArray,
            originalFilename: String?,
            logName: String,
        ): ProcessMUploadResponse = error("uploadLog is not used by VerificationService")

        override fun listDataStores(): List<RemoteProcessMDataStore> =
            error("listDataStores is not used by VerificationService")
    }

    private fun <T> anyArg(): T = Mockito.any<T>()

    private fun xesLogWithTraces(vararg traces: Map<String, Any?>): List<Map<String, Any?>> =
        listOf(mapOf("log" to mapOf("trace" to traces.toList())))

    private fun trace(name: String): Map<String, Any?> =
        mapOf("event" to mapOf("string" to mapOf("@key" to "concept:name", "@value" to name)))

    private fun domainTrace(name: String): XesTrace =
        XesTrace(events = listOf(XesEvent(conceptName = name)))
}
