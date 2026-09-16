package com.example.loanlimit.bank

import com.example.loanlimit.bankcallresult.dto.MockExternalCallResult
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import reactor.core.publisher.Mono
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

    /**
     * webclient 모드용. mono { callApiNonBlocking(...) } 로 감싸면 코루틴
     * 디스패처를 거치게 되어 그 모드가 coroutine과 같아진다. 두 모드의
     * 차이가 그 지점이므로 Mono를 그대로 반환한다.
     */
    fun callApiReactive(
        request: LoanLimitQueryRequest,
        requestPayload: String,
    ): Mono<MockExternalCallResult>

    /**
     * 실패 행. 모드마다 따로 만들면 host가 어긋나므로 여기서만 만든다.
     */
    fun toFailureEntity(
        runId: Long,
        requestPayload: String,
        responseCode: String,
        responseMessage: String,
        errorDetail: String?,
        latencyMs: Long,
        requestedAt: LocalDateTime,
        respondedAt: LocalDateTime,
    ): BankCallResult

    fun toEntity(
        runId: Long,
        requestPayload: String,
        response: MockExternalCallResult,
        requestedAt: LocalDateTime,
        respondedAt: LocalDateTime,
        latencyMs: Long,
    ): BankCallResult
}
