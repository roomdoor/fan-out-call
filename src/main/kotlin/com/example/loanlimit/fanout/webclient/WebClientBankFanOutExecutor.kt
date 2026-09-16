package com.example.loanlimit.fanout.webclient

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.fanout.BankFanOutExecutor
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.bank.BankApiServiceRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
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
        val requestedAt = LocalDateTime.now()
        val started = Instant.now()

        // 준비 단계까지 defer 안에 둔다. 밖에서 던지면 flatMap 매퍼가 터져
        // Flux 전체가 끝나고 나머지 은행의 구독이 취소된다.
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
