package com.example.loanlimit.fanout

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.config.AsyncExecutionConfig
import com.example.loanlimit.domain.LenderApiProfile
import com.example.loanlimit.dto.LoanLimitQueryRequest
import com.example.loanlimit.entity.LenderCallResultEntity
import com.example.loanlimit.lender.LenderApiServiceRegistry
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
class AsyncLenderCallWorker(
    private val appProperties: AppProperties,
    private val lenderApiServiceRegistry: LenderApiServiceRegistry,
) {
    @Async(AsyncExecutionConfig.LENDER_ASYNC_EXECUTOR)
    fun call(
        runId: Long,
        profile: LenderApiProfile,
        request: LoanLimitQueryRequest,
    ): CompletableFuture<LenderCallResultEntity> {
        val lenderService = lenderApiServiceRegistry.get(profile.lenderCode)
        val requestedAt = LocalDateTime.now()
        val started = Instant.now()
        val requestPayload = lenderService.buildRequest(request)

        val result = try {
            val response = runBlocking {
                withTimeout(appProperties.lenders.perCallTimeoutMs) {
                    lenderService.callApi(profile, request, requestPayload)
                }
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

        return CompletableFuture.completedFuture(result)
    }

    companion object {
        private val log = LoggerFactory.getLogger(AsyncLenderCallWorker::class.java)
    }
}
