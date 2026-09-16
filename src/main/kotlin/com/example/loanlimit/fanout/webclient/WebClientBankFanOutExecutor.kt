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

        // 호출은 다른 모드와 같은 ExternalBankApiService를 쓴다. 이 모드만
        // webClient.post()를 직접 만들고 있어서 공유 커넥션 풀 설정이
        // 적용되지 않던 문제가 있었다.
        //
        // 다만 mono { callApiNonBlocking(...) } 로 감싸면 안 된다. 호출마다
        // 코루틴 디스패처를 한 번 거치게 되어 이 모드가 coroutine 모드와
        // 같아진다. 두 모드의 차이가 정확히 그 지점이므로 Mono를 그대로 받는다.
        //
        // 준비 단계(registry.get, buildRequest)도 Mono 안에서 실행한다.
        // 밖에 두면 flatMap 매퍼에서 던져 Flux 전체가 에러로 끝나고 나머지
        // 은행의 구독이 취소된다. defer 안이면 에러 신호가 되어 아래
        // onErrorResume이 그 은행의 실패로 바꿔준다.
        val payloadRef = AtomicReference("{}")

        return Mono.defer {
            val bankService = bankApiServiceRegistry.get(bankCode)
            val requestPayload = bankService.buildRequest(request)
            payloadRef.set(requestPayload)

            bankService.callApiReactive(request, requestPayload)
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
                    bankApiServiceRegistry.toFailureEntity(
                        runId = runId,
                        bankCode = bankCode,
                        requestPayload = payloadRef.get(),
                        responseCode = "EXCEPTION",
                        responseMessage = "External call failed",
                        errorDetail = e.message,
                        latencyMs = latencyMs,
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
