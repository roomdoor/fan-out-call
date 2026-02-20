package com.example.loanlimit.fanout.webclient

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.fanout.BankFanOutExecutor
import com.example.loanlimit.bankcallresult.dto.MockExternalCallResult
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.bank.BankApiServiceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime

@Component
class WebClientBankFanOutExecutor(
    private val appProperties: AppProperties,
    private val webClientBuilder: WebClient.Builder,
    private val bankApiServiceRegistry: BankApiServiceRegistry,
) : BankFanOutExecutor {
    private val webClient: WebClient by lazy {
        webClientBuilder.build()
    }

    override suspend fun execute(
        runId: Long,
        banks: List<String>,
        request: LoanLimitQueryRequest,
        onEachResult: suspend (BankCallResult) -> Unit,
    ) {
        log.info(
            "WebClient fan-out started runId=$runId bankCount=${banks.size} " +
                "maxConcurrency=${appProperties.webClientFanOut.maxConcurrency} " +
                "perCallTimeoutMs=${appProperties.banks.perCallTimeoutMs}",
        )

        Flux.fromIterable(banks)
            .flatMap(
                { bank -> callSingleBank(runId, bank, request) },
                appProperties.webClientFanOut.maxConcurrency,
            )
            .flatMap(
                { result -> mono(Dispatchers.IO) { onEachResult(result) } },
                appProperties.webClientFanOut.maxConcurrency,
            )
            .then()
            .awaitSingleOrNull()

        log.info("WebClient fan-out finished runId=$runId bankCount=${banks.size}")
    }

    private fun callSingleBank(
        runId: Long,
        bankCode: String,
        request: LoanLimitQueryRequest,
    ): Mono<BankCallResult> {
        val bankService = bankApiServiceRegistry.get(bankCode)
        val mockBaseUrl = appProperties.webClientFanOut.resolveMockBaseUrl(bankCode)
        val bankApiPath = "/api/v1/mock-external/banks/$bankCode/loan-limit"
        val requestedAt = LocalDateTime.now()
        val started = Instant.now()
        val requestPayload = bankService.buildRequest(request)

        return webClient.post()
            .uri("$mockBaseUrl$bankApiPath")
            .bodyValue(request)
            .retrieve()
            .bodyToMono<MockExternalCallResult>()
            .timeout(Duration.ofMillis(appProperties.banks.perCallTimeoutMs))
            .map { response ->
                bankService.toEntity(
                    runId = runId,
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
                    "Bank call failed runId=$runId bankCode=$bankCode latencyMs=$latencyMs " +
                        "errorType=${e::class.simpleName} message=${e.message}",
                )

                Mono.just(
                    BankCallResult(
                        runId = runId,
                        bankCode = bankCode,
                        host = mockBaseUrl,
                        url = bankApiPath,
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
        private val log = LoggerFactory.getLogger(WebClientBankFanOutExecutor::class.java)
    }
}
