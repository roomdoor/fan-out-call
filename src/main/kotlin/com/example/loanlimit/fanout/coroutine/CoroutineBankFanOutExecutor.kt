package com.example.loanlimit.fanout.coroutine

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.fanout.BankFanOutExecutor
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.bank.BankApiServiceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

@Component
class CoroutineBankFanOutExecutor(
    private val appProperties: AppProperties,
    private val bankApiServiceRegistry: BankApiServiceRegistry,
) : BankFanOutExecutor {
    override suspend fun execute(
        runId: Long,
        banks: List<String>,
        request: LoanLimitQueryRequest,
        onEachResult: suspend (BankCallResult) -> Unit,
    ) {
        val semaphore = Semaphore(appProperties.banks.parallelism)
        log.info(
            "Fan-out execution started runId=$runId bankCount=${banks.size} " +
                "parallelism=${appProperties.banks.parallelism} " +
                "perCallTimeoutMs=${appProperties.banks.perCallTimeoutMs}",
        )

        coroutineScope {
            banks.map { bank ->
                async(Dispatchers.IO) {
                    val result = semaphore.withPermit {
                        executeSingleCall(runId, bank, request)
                    }
                    onEachResult(result)
                }
            }.awaitAll()
        }

        log.info("Fan-out execution finished runId=$runId bankCount=${banks.size}")
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

    companion object {
        private val log = LoggerFactory.getLogger(CoroutineBankFanOutExecutor::class.java)
    }
}
