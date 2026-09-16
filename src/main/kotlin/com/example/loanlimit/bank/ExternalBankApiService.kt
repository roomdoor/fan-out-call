package com.example.loanlimit.bank

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.bankcallresult.dto.MockExternalCallResult
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.LocalDateTime

class ExternalBankApiService(
    override val bankCode: String,
    private val appProperties: AppProperties,
    private val webClient: WebClient,
) : BankApiService {
    private val mockBaseUrl: String by lazy {
        appProperties.webClientFanOut.resolveMockBaseUrl(bankCode)
    }

    private val bankApiPath: String = "/api/v1/mock-external/banks/$bankCode/loan-limit"

    private val bankApiUrl: String by lazy { "$mockBaseUrl$bankApiPath" }

    override fun buildRequest(request: LoanLimitQueryRequest): String {
        return """{"customer":{"id":"${request.borrowerId}"},"income":{"annual":${request.annualIncome}},"loan":{"requestedAmount":${request.requestedAmount}}}"""
    }

    // 호출 자체는 한 곳에서 만든다. 세 메소드는 이 Mono를 어떻게 소비하느냐만
    // 다르다 — block(), awaitSingle(), 그대로 반환.
    override fun callApiReactive(
        request: LoanLimitQueryRequest,
        requestPayload: String,
    ): Mono<MockExternalCallResult> {
        return webClient.post()
            .uri(bankApiUrl)
            .bodyValue(request)
            .attribute("bankCode", bankCode)
            .retrieve()
            .bodyToMono(MockExternalCallResult::class.java)
            .timeout(Duration.ofMillis(appProperties.banks.perCallTimeoutMs))
    }

    override suspend fun callApi(
        request: LoanLimitQueryRequest,
        requestPayload: String,
    ): MockExternalCallResult {
        return callApiReactive(request, requestPayload).block()
            ?: error("Empty response from fake bank server bankCode=$bankCode")
    }

    override suspend fun callApiNonBlocking(
        request: LoanLimitQueryRequest,
        requestPayload: String,
    ): MockExternalCallResult {
        return callApiReactive(request, requestPayload).awaitSingle()
    }

    override fun toFailureEntity(
        runId: Long,
        requestPayload: String,
        responseCode: String,
        responseMessage: String,
        errorDetail: String?,
        latencyMs: Long,
        requestedAt: LocalDateTime,
        respondedAt: LocalDateTime,
    ): BankCallResult {
        // mockBaseUrl 은 resolveMockBaseUrl 을 부르는 lazy 라서 던질 수 있다.
        // 샤드 라우팅에서 은행 코드가 BANK-\d{2} 형식을 벗어나거나
        // shard-count/mock-base-url 이 잘못되면 require 가 터진다.
        //
        // 이 함수는 실패를 한 은행에 가두려고 부르는 자리다. 여기서 던지면
        // 격리가 깨져 호출부의 catch/onErrorResume 밖으로 나가고, 모드마다
        // 형제 은행이 취소되거나 루프가 멈춘다.
        val resolvedHost = runCatching { mockBaseUrl }.getOrDefault("unresolved")

        return BankCallResult(
            runId = runId,
            bankCode = bankCode,
            host = resolvedHost,
            url = bankApiPath,
            httpStatus = null,
            success = false,
            responseCode = responseCode,
            responseMessage = responseMessage,
            approvedLimit = null,
            latencyMs = latencyMs,
            errorDetail = errorDetail,
            requestPayload = requestPayload,
            responsePayload = "{}",
            requestedAt = requestedAt,
            respondedAt = respondedAt,
        )
    }

    override fun toEntity(
        runId: Long,
        requestPayload: String,
        response: MockExternalCallResult,
        requestedAt: LocalDateTime,
        respondedAt: LocalDateTime,
        latencyMs: Long,
    ): BankCallResult {
        val success = response.httpStatus in 200..299 && response.approvedLimit != null

        return BankCallResult(
            runId = runId,
            bankCode = bankCode,
            host = mockBaseUrl,
            url = bankApiPath,
            httpStatus = response.httpStatus,
            success = success,
            responseCode = response.responseCode,
            responseMessage = response.responseMessage,
            approvedLimit = response.approvedLimit,
            latencyMs = latencyMs,
            errorDetail = if (success) null else response.responseMessage,
            requestPayload = requestPayload,
            responsePayload = response.responsePayload,
            requestedAt = requestedAt,
            respondedAt = respondedAt,
        )
    }
}
