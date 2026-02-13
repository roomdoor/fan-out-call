package com.example.loanlimit.fanout

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.domain.LenderApiProfile
import com.example.loanlimit.domain.MockExternalCallResult
import com.example.loanlimit.dto.LoanLimitQueryRequest
import com.example.loanlimit.entity.LenderCallResultEntity
import com.example.loanlimit.lender.LenderApiServiceRegistry
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

@Component
class WebClientLenderFanOutExecutor(
    private val appProperties: AppProperties,
    private val webClientBuilder: WebClient.Builder,
    private val lenderApiServiceRegistry: LenderApiServiceRegistry,
) : LenderFanOutExecutor {
    private val webClient: WebClient by lazy {
        webClientBuilder
            .baseUrl(appProperties.webClientFanOut.mockBaseUrl)
            .build()
    }

    override suspend fun execute(
        runId: Long,
        lenders: List<LenderApiProfile>,
        request: LoanLimitQueryRequest,
        onEachResult: suspend (LenderCallResultEntity) -> Unit,
    ) {
        log.info(
            "WebClient fan-out started runId=$runId lenderCount=${lenders.size} " +
                "maxConcurrency=${appProperties.webClientFanOut.maxConcurrency} " +
                "perCallTimeoutMs=${appProperties.lenders.perCallTimeoutMs}",
        )

        Flux.fromIterable(lenders)
            .flatMap(
                { lender -> callSingleLender(runId, lender, request) },
                appProperties.webClientFanOut.maxConcurrency,
            )
            .publishOn(Schedulers.boundedElastic())
            .doOnNext { result ->
                runBlocking {
                    onEachResult(result)
                }
            }
            .then()
            .block()

        log.info("WebClient fan-out finished runId=$runId lenderCount=${lenders.size}")
    }

    private fun callSingleLender(
        runId: Long,
        profile: LenderApiProfile,
        request: LoanLimitQueryRequest,
    ): Mono<LenderCallResultEntity> {
        val lenderService = lenderApiServiceRegistry.get(profile.lenderCode)
        val requestedAt = LocalDateTime.now()
        val started = Instant.now()
        val requestPayload = lenderService.buildRequest(request)

        return webClient.post()
            .uri("/api/v1/mock-external/lenders/{lenderCode}/loan-limit", profile.lenderCode)
            .bodyValue(request)
            .retrieve()
            .bodyToMono(MockExternalCallResult::class.java)
            .timeout(Duration.ofMillis(appProperties.lenders.perCallTimeoutMs))
            .map { response ->
                lenderService.toEntity(
                    runId = runId,
                    profile = profile,
                    requestPayload = requestPayload,
                    response = response,
                    requestedAt = requestedAt,
                    respondedAt = LocalDateTime.now(),
                    latencyMs = Duration.between(started, Instant.now()).toMillis(),
                )
            }
            .onErrorResume { e ->
                val latencyMs = Duration.between(started, Instant.now()).toMillis()
                log.warn(
                    "Lender call failed runId=$runId lenderCode=${profile.lenderCode} latencyMs=$latencyMs " +
                        "errorType=${e::class.simpleName} message=${e.message}",
                )

                Mono.just(
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
                    ),
                )
            }
    }

    companion object {
        private val log = LoggerFactory.getLogger(WebClientLenderFanOutExecutor::class.java)
    }
}
