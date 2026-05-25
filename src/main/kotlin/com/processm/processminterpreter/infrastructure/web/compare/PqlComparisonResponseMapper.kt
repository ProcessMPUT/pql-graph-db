package com.processm.processminterpreter.infrastructure.web.compare

import com.processm.processminterpreter.application.compatibility.VerifyPqlQueryResult
import com.processm.processminterpreter.infrastructure.web.compare.dto.PQLVerificationResponse
import org.springframework.stereotype.Component

@Component
class PqlComparisonResponseMapper {
    fun toVerificationResponse(result: VerifyPqlQueryResult): PQLVerificationResponse =
        PQLVerificationResponse(
            match = result.match,
            localSuccess = result.localSuccess,
            remoteSuccess = result.remoteSuccess,
            localCount = result.localCount,
            remoteCount = result.remoteCount,
            localResults = result.localResults,
            remoteResults = result.remoteResults,
            remoteRequestUrl = result.remoteRequestUrl,
            remoteAdaptedQuery = result.remoteAdaptedQuery,
            remoteDataStoreId = result.remoteDataStoreId,
            details = result.details,
        )

    fun toVerificationErrorResponse(message: String): PQLVerificationResponse =
        PQLVerificationResponse(
            match = false,
            localSuccess = false,
            remoteSuccess = false,
            details = message,
        )
}
