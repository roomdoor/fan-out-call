package com.example.loanlimit.fanout.sequential

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.fanout.BankFanOutExecutor
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.bank.BankApiServiceRegistry
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.Executors

@Component
class SequentialSingleThreadBankFanOutExecutor(
    private val appProperties: AppProperties,
    private val bankApiServiceRegistry: BankApiServiceRegistry,
) : BankFanOutExecutor {
    private val singleThreadExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "loan-limit-sequential-fanout")
    }
    private val singleThreadDispatcher = singleThreadExecutor.asCoroutineDispatcher()

    override suspend fun execute(
        runId: Long,
        banks: List<String>,
        request: LoanLimitQueryRequest,
        onEachResult: suspend (BankCallResult) -> Unit,
    ) {
        log.info(
            "Sequential fan-out execution started runId=$runId bankCount=${banks.size} " +
                "singleThread=loan-limit-sequential-fanout perCallTimeoutMs=${appProperties.banks.perCallTimeoutMs}",
        )

        withContext(singleThreadDispatcher) {
            for (bank in banks) {
                val result = executeSingleCall(runId, bank, request)
                onEachResult(result)
            }
        }

        log.info("Sequential fan-out execution finished runId=$runId bankCount=${banks.size}")
    }

    private suspend fun executeSingleCall(
        runId: Long,
        bankCode: String,
        request: LoanLimitQueryRequest,
    ): BankCallResult {
        val bankService = bankApiServiceRegistry.get(bankCode)
        val requestedAt = LocalDateTime.now()
        val started = Instant.now()
        val requestPayload = bankService.buildRequest(request)

        return try {
            val response = withTimeout(appProperties.banks.perCallTimeoutMs) {
                bankService.callApi(request, requestPayload)
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
    }

    @PreDestroy
    fun shutdown() {
        singleThreadDispatcher.close()
        singleThreadExecutor.shutdown()
    }

    companion object {
        private val log = LoggerFactory.getLogger(SequentialSingleThreadBankFanOutExecutor::class.java)
    }
}
