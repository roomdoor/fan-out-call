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
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicReference
import com.example.loanlimit.logging.MdcKeys

@Component
class WebClientBankFanOutExecutor(
    private val appProperties: AppProperties,
    @Qualifier("sharedBankWebClient") private val webClient: WebClient,
    private val bankApiServiceRegistry: BankApiServiceRegistry,
) : BankFanOutExecutor {

    override suspend fun execute(
        runId: Long,
        banks: List<String>,
        request: LoanLimitQueryRequest,
        onEachResult: suspend (BankCallResult) -> Unit,
    ) {
        log.info(
            "WebClient fan-out started bankCount=${banks.size} " +
                "maxConcurrency=${appProperties.webClientFanOut.maxConcurrency} " +
                "perCallTimeoutMs=${appProperties.banks.perCallTimeoutMs}",
        )

        val baseMdc = MDC.getCopyOfContextMap() ?: emptyMap()

        Flux.fromIterable(banks)
            .flatMap(
                { bank -> callSingleBank(runId, bank, request) },
                appProperties.webClientFanOut.maxConcurrency,
            )
            .flatMap(
                { result ->
                    mono(Dispatchers.IO + MDCContext(baseMdc + mapOf(MdcKeys.BANK_CODE to result.bankCode))) {
                        onEachResult(result)
                    }
                },
                appProperties.webClientFanOut.maxConcurrency,
            )
            .then()
            .awaitSingleOrNull()

        log.info("WebClient fan-out finished bankCount=${banks.size}")
    }

    private fun callSingleBank(
        runId: Long,
        bankCode: String,
        request: LoanLimitQueryRequest,
    ): Mono<BankCallResult> {
        val bankApiPath = "/api/v1/mock-external/banks/$bankCode/loan-limit"
        val requestedAt = LocalDateTime.now()
        val started = Instant.now()

        // 준비 단계(registry.get, resolveMockBaseUrl, buildRequest)도 Mono 안에서
        // 실행한다. 밖에 두면 flatMap 매퍼에서 던져 Flux 전체가 에러로 끝나고
        // 나머지 은행의 구독이 취소된다. defer 안에서 던지면 에러 신호가 되어
        // 아래 onErrorResume이 그 은행의 실패로 바꿔준다.
        //
        // onErrorResume이 값을 읽어야 하므로 참조로 넘긴다. 준비 단계에서 터지면
        // 기본값이 그대로 쓰인다.
        val payloadRef = AtomicReference("{}")
        val hostRef = AtomicReference(appProperties.webClientFanOut.mockBaseUrl)

        return Mono.defer {
            val bankService = bankApiServiceRegistry.get(bankCode)
            val mockBaseUrl = appProperties.webClientFanOut.resolveMockBaseUrl(bankCode)
            hostRef.set(mockBaseUrl)
            val requestPayload = bankService.buildRequest(request)
            payloadRef.set(requestPayload)

            webClient.post()
                .uri("$mockBaseUrl$bankApiPath")
                .bodyValue(request)
                .attribute("bankCode", bankCode)
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
        }
            .onErrorResume { e ->
                val latencyMs = Duration.between(started, Instant.now()).toMillis()
                log.warn("Bank call failed latencyMs=$latencyMs errorType=${e::class.simpleName} message=${e.message}")

                Mono.just(
                    BankCallResult(
                        runId = runId,
                        bankCode = bankCode,
                        host = hostRef.get(),
                        url = bankApiPath,
                        httpStatus = null,
                        success = false,
                        responseCode = "EXCEPTION",
                        responseMessage = "External call failed",
                        approvedLimit = null,
                        latencyMs = latencyMs,
                        errorDetail = e.message,
                        requestPayload = payloadRef.get(),
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
