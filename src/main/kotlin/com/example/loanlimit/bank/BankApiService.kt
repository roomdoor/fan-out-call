package com.example.loanlimit.bank

import com.example.loanlimit.bankcallresult.dto.MockExternalCallResult
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import java.time.LocalDateTime

interface BankApiService {
    val bankCode: String

    fun buildRequest(request: LoanLimitQueryRequest): String

    suspend fun callApi(
        request: LoanLimitQueryRequest,
        requestPayload: String,
    ): MockExternalCallResult

    suspend fun callApiNonBlocking(
        request: LoanLimitQueryRequest,
        requestPayload: String,
    ): MockExternalCallResult {
        return callApi(request, requestPayload)
    }

    fun toEntity(
        runId: Long,
        requestPayload: String,
        response: MockExternalCallResult,
        requestedAt: LocalDateTime,
        respondedAt: LocalDateTime,
        latencyMs: Long,
    ): BankCallResult
}
