package com.example.loanlimit.bank

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.bankcallresult.dto.MockExternalCallResult
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.LocalDateTime

class ExternalBankApiService(
    override val bankCode: String,
    private val appProperties: AppProperties,
    private val webClientBuilder: WebClient.Builder,
) : BankApiService {
    private val webClient: WebClient by lazy {
        webClientBuilder
            .baseUrl(appProperties.webClientFanOut.mockBaseUrl)
            .build()
    }

    override fun buildRequest(request: LoanLimitQueryRequest): String {
        return """{"customer":{"id":"${request.borrowerId}"},"income":{"annual":${request.annualIncome}},"loan":{"requestedAmount":${request.requestedAmount}}}"""
    }

    override suspend fun callApi(
        request: LoanLimitQueryRequest,
        requestPayload: String,
    ): MockExternalCallResult {
        return webClient.post()
            .uri("/api/v1/mock-external/banks/{bankCode}/loan-limit", bankCode)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(MockExternalCallResult::class.java)
            .timeout(Duration.ofMillis(appProperties.banks.perCallTimeoutMs))
            .block()
            ?: error("Empty response from fake bank server bankCode=$bankCode")
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
            host = appProperties.webClientFanOut.mockBaseUrl,
            url = "/api/v1/mock-external/banks/$bankCode/loan-limit",
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
