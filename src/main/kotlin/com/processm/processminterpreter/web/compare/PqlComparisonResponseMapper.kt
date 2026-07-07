package com.processm.processminterpreter.web.compare

import com.processm.processminterpreter.processm.compat.VerifyPqlQueryResult
import com.processm.processminterpreter.web.compare.dto.PQLVerificationResponse
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
            comparisonStatus = result.comparisonStatus,
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
