package com.example.loanlimit.fanout.asyncpool

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.config.AsyncExecutionConfig
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.bank.BankApiServiceRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture

@Component
class AsyncBankCallWorker(
    private val appProperties: AppProperties,
    private val bankApiServiceRegistry: BankApiServiceRegistry,
) {
    @Async(AsyncExecutionConfig.BANK_ASYNC_EXECUTOR)
    fun call(
        runId: Long,
        bankCode: String,
        request: LoanLimitQueryRequest,
    ): CompletableFuture<BankCallResult> {
        val bankService = bankApiServiceRegistry.get(bankCode)
        val requestedAt = LocalDateTime.now()
        val started = Instant.now()
        val requestPayload = bankService.buildRequest(request)

        val result = try {
            val response = runBlocking {
                withTimeout(appProperties.banks.perCallTimeoutMs) {
                    bankService.callApi(request, requestPayload)
                }
            }

            bankService.toEntity(
                runId = runId,
                requestPayload = requestPayload,
                response = response,
                requestedAt = requestedAt,
                respondedAt = LocalDateTime.now(),
                latencyMs = Duration.between(started, Instant.now()).toMillis(),
            )
        } catch (e: Exception) {
            val latencyMs = Duration.between(started, Instant.now()).toMillis()
            log.warn(
                "Bank call failed runId=$runId bankCode=$bankCode latencyMs=$latencyMs " +
                    "errorType=${e::class.simpleName} message=${e.message}",
            )

            BankCallResult(
                runId = runId,
                bankCode = bankCode,
                host = appProperties.webClientFanOut.mockBaseUrl,
                url = "/api/v1/mock-external/banks/$bankCode/loan-limit",
                httpStatus = null,
                success = false,
                responseCode = "EXCEPTION",
                responseMessage = "External call failed",
                approvedLimit = null,
                latencyMs = latencyMs,
                errorDetail = e.message,
                requestPayload = requestPayload,
                responsePayload = "{}",
                requestedAt = requestedAt,
                respondedAt = LocalDateTime.now(),
            )
        }

        return CompletableFuture.completedFuture(result)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AsyncBankCallWorker::class.java)
    }
}
