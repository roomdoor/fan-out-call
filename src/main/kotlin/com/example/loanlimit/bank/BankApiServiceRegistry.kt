package com.example.loanlimit.bank

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.bankcallresult.service.BankCatalogService
import java.time.LocalDateTime
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.stereotype.Component

@Component
class BankApiServiceRegistry(
    bankCatalogService: BankCatalogService,
    appProperties: AppProperties,
    @Qualifier("sharedBankWebClient") webClient: WebClient,
) {
    private val servicesByBankCode: Map<String, BankApiService> = bankCatalogService.getAll().associate { bankCode ->
        bankCode to ExternalBankApiService(
            bankCode = bankCode,
            appProperties = appProperties,
            webClient = webClient,
        )
    }

    fun get(bankCode: String): BankApiService {
        return servicesByBankCode[bankCode]
            ?: error("No bank service registered for bankCode: $bankCode")
    }

    /**
     * 실패 행 생성. 네 모드가 모두 이걸 쓴다 — 따로 만들면 host가 어긋난다.
     * 등록되지 않은 은행 코드도 여기서 받는다(get()이 던지는 경우).
     */
    fun toFailureEntity(
        runId: Long,
        bankCode: String,
        requestPayload: String,
        responseCode: String,
        responseMessage: String,
        errorDetail: String?,
        latencyMs: Long,
        requestedAt: LocalDateTime,
        respondedAt: LocalDateTime,
    ): BankCallResult {
        servicesByBankCode[bankCode]?.let { service ->
            return service.toFailureEntity(
                runId = runId,
                requestPayload = requestPayload,
                responseCode = responseCode,
                responseMessage = responseMessage,
                errorDetail = errorDetail,
                latencyMs = latencyMs,
                requestedAt = requestedAt,
                respondedAt = respondedAt,
            )
        }

        // 카탈로그에 없는 은행. 주소를 만들 근거가 없으므로 그대로 남긴다.
        return BankCallResult(
            runId = runId,
            bankCode = bankCode,
            host = "unregistered",
            url = "/api/v1/mock-external/banks/$bankCode/loan-limit",
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
}
