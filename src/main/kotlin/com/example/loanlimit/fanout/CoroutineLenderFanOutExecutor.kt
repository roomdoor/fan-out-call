package com.example.loanlimit.fanout

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.domain.LenderApiProfile
import com.example.loanlimit.dto.LoanLimitQueryRequest
import com.example.loanlimit.entity.LenderCallResultEntity
import com.example.loanlimit.lender.LenderApiServiceRegistry
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
class CoroutineLenderFanOutExecutor(
    private val appProperties: AppProperties,
    private val lenderApiServiceRegistry: LenderApiServiceRegistry,
) : LenderFanOutExecutor {
    override suspend fun execute(
        runId: Long,
        lenders: List<LenderApiProfile>,
        request: LoanLimitQueryRequest,
        onEachResult: suspend (LenderCallResultEntity) -> Unit,
    ) {
        val semaphore = Semaphore(appProperties.lenders.parallelism)
        log.info(
            "Fan-out execution started runId=$runId lenderCount=${lenders.size} " +
                "parallelism=${appProperties.lenders.parallelism} " +
                "perCallTimeoutMs=${appProperties.lenders.perCallTimeoutMs}",
        )

        coroutineScope {
            lenders.map { lender ->
                async(Dispatchers.IO) {
                    val result = semaphore.withPermit {
                        executeSingleCall(runId, lender, request)
                    }
                    onEachResult(result)
                }
            }.awaitAll()
        }

        log.info("Fan-out execution finished runId=$runId lenderCount=${lenders.size}")
    }

    private suspend fun executeSingleCall(
        runId: Long,
        profile: LenderApiProfile,
        request: LoanLimitQueryRequest,
    ): LenderCallResultEntity {
        val lenderService = lenderApiServiceRegistry.get(profile.lenderCode)
        val requestedAt = LocalDateTime.now()
        val started = Instant.now()
        val requestPayload = lenderService.buildRequest(request)

        return try {
            val response = withTimeout(appProperties.lenders.perCallTimeoutMs) {
                lenderService.callApi(profile, request, requestPayload)
            }

            lenderService.toEntity(
                runId = runId,
                profile = profile,
                requestPayload = requestPayload,
                response = response,
                requestedAt = requestedAt,
                respondedAt = LocalDateTime.now(),
                latencyMs = Duration.between(started, Instant.now()).toMillis(),
            )
        } catch (e: Exception) {
            val latencyMs = Duration.between(started, Instant.now()).toMillis()
            log.warn(
                "Lender call failed runId=$runId lenderCode=${profile.lenderCode} latencyMs=$latencyMs " +
                    "errorType=${e::class.simpleName} message=${e.message}",
            )

            LenderCallResultEntity(
                runId = runId,
                lenderCode = profile.lenderCode,
                host = profile.host,
                url = profile.url,
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
        private val log = LoggerFactory.getLogger(CoroutineLenderFanOutExecutor::class.java)
    }
}
