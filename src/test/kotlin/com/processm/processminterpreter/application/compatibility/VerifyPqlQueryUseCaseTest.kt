package com.processm.processminterpreter.application.compatibility

import com.processm.processminterpreter.application.ports.ProcessMJsonFormatter
import com.processm.processminterpreter.application.ports.ProcessMUploadResponse
import com.processm.processminterpreter.application.ports.RemoteProcessMDataStore
import com.processm.processminterpreter.application.ports.RemoteProcessMGateway
import com.processm.processminterpreter.application.ports.RemoteQueryExecutionResult
import com.processm.processminterpreter.application.query.ExecutePqlQueryUseCase
import com.processm.processminterpreter.domain.pql.catalog.SourceLocation
import com.processm.processminterpreter.domain.pql.error.PQLSyntaxException
import com.processm.processminterpreter.domain.pql.error.Problem
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class VerifyPqlQueryUseCaseTest {

    @Test
    fun `matching known PQL failures are treated as compatible`() {
        val executeUseCase = Mockito.mock(ExecutePqlQueryUseCase::class.java)
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
    fun `unknown failures are not treated as compatible just because both sides failed`() {
        val executeUseCase = Mockito.mock(ExecutePqlQueryUseCase::class.java)
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

    private fun useCase(
        executeUseCase: ExecutePqlQueryUseCase,
        remote: RemoteProcessMGateway,
    ): VerifyPqlQueryUseCase =
        VerifyPqlQueryUseCase(
            executeUseCase = executeUseCase,
            remoteProcessM = remote,
            formatter = ProcessMJsonFormatter { emptyList() },
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
        ): ProcessMUploadResponse = error("uploadLog is not used by VerifyPqlQueryUseCase")

        override fun listDataStores(): List<RemoteProcessMDataStore> =
            error("listDataStores is not used by VerifyPqlQueryUseCase")
    }

    private fun <T> anyArg(): T = Mockito.any<T>()
}
