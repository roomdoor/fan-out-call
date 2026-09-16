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
     * Reactor 체인용. webclient 모드는 Flux.flatMap 에 Mono를 넘겨야 해서
     * suspend 메소드를 쓸 수 없다.
     *
     * mono { callApiNonBlocking(...) } 로 감싸면 호출마다 코루틴 디스패처를
     * 한 번 거치게 되어 webclient 모드가 coroutine 모드와 같아진다.
     * 두 모드의 차이가 그 지점이므로 Mono를 그대로 반환한다.
     */
    fun callApiReactive(
        request: LoanLimitQueryRequest,
        requestPayload: String,
    ): Mono<MockExternalCallResult>

    /**
     * 호출이 결과를 만들지 못했을 때의 실패 행.
     *
     * 모드마다 따로 만들면 같은 은행이 모드에 따라 다른 host로 기록된다.
     * 실제로 그랬다 — coroutine/sequential은 mockBaseUrl(샤딩 무시),
     * async-threadpool은 resolveMockBaseUrl(샤드 반영)을 쓰고 있었다.
     * 은행별 주소를 아는 건 이 서비스이므로 여기서 만든다.
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
