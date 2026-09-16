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
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import com.example.loanlimit.logging.MdcKeys
import org.slf4j.MDC
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
        val baseMdc = MDC.getCopyOfContextMap() ?: emptyMap()
        log.info(
            "Sequential fan-out execution started bankCount=${banks.size} " +
                "singleThread=loan-limit-sequential-fanout perCallTimeoutMs=${appProperties.banks.perCallTimeoutMs}",
        )

        withContext(singleThreadDispatcher) {
            for (bank in banks) {
                val result = executeSingleCall(runId, bank, request)
                withContext(MDCContext(baseMdc + mapOf(MdcKeys.BANK_CODE to bank))) {
                    onEachResult(result)
                }
            }
        }

        log.info("Sequential fan-out execution finished bankCount=${banks.size}")
    }

    private suspend fun executeSingleCall(
        runId: Long,
        bankCode: String,
        request: LoanLimitQueryRequest,
    ): BankCallResult {
        val requestedAt = LocalDateTime.now()
        val started = Instant.now()
        // registry.get() 과 buildRequest() 도 try 안에 둔다. 밖에 두면 은행 하나의
        // 문제로 for 루프가 중단되어 남은 은행을 호출조차 하지 않는다.
        var requestPayload = "{}"

        return try {
            val bankService = bankApiServiceRegistry.get(bankCode)
            requestPayload = bankService.buildRequest(request)

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
            log.warn("Bank call failed latencyMs=$latencyMs errorType=${e::class.simpleName} message=${e.message}")

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
