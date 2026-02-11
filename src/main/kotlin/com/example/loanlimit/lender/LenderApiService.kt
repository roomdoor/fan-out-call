package com.example.loanlimit.lender

import com.example.loanlimit.domain.LenderApiProfile
import com.example.loanlimit.domain.MockExternalCallResult
import com.example.loanlimit.dto.LoanLimitQueryRequest
import com.example.loanlimit.entity.LenderCallResultEntity
import java.time.LocalDateTime

interface LenderApiService {
    val lenderCode: String

    fun buildRequest(request: LoanLimitQueryRequest): String

    suspend fun callApi(
        profile: LenderApiProfile,
        request: LoanLimitQueryRequest,
        requestPayload: String,
    ): MockExternalCallResult

    fun toEntity(
        runId: Long,
        profile: LenderApiProfile,
        requestPayload: String,
        response: MockExternalCallResult,
        requestedAt: LocalDateTime,
        respondedAt: LocalDateTime,
        latencyMs: Long,
    ): LenderCallResultEntity
}
