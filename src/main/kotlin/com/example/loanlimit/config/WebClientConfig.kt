package com.example.loanlimit.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.ExchangeFilterFunction
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import com.example.loanlimit.logging.MdcKeys
import com.example.loanlimit.logging.restoreMdc
import reactor.netty.http.client.HttpClient
import reactor.netty.resources.ConnectionProvider
import java.time.Duration

@Configuration
class WebClientConfig {
    @Bean
    fun webClientBuilder(): WebClient.Builder {
        return WebClient.builder()
            .filter(bankCallLoggingFilter())
    }

    // 모든 ExternalBankApiService가 공유하는 단일 WebClient — 커넥션 풀을 한 곳에서 관리
    @Bean
    fun sharedBankWebClient(): WebClient {
        val connectionProvider = ConnectionProvider.builder("shared-bank-pool")
            .maxConnections(2000)
            .pendingAcquireMaxCount(10000)
            .pendingAcquireTimeout(Duration.ofSeconds(60))
            .build()
        return WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(HttpClient.create(connectionProvider)))
            .filter(bankCallLoggingFilter())
            .build()
    }

    private fun bankCallLoggingFilter(): ExchangeFilterFunction {
        return ExchangeFilterFunction { request, next ->
            val path = request.url().path
            if (!path.startsWith("/api/v1/mock-external/banks/")) {
                return@ExchangeFilterFunction next.exchange(request)
            }

            val bankCode = request.attribute("bankCode").orElse(null)

            val previous = MDC.getCopyOfContextMap()
            if (bankCode != null) MDC.put(MdcKeys.BANK_CODE, bankCode.toString()) else MDC.remove(MdcKeys.BANK_CODE)

            val started = System.nanoTime()
            log.debug("Bank HTTP request method=${request.method()} url=${request.url()}")

            next.exchange(request)
                .doOnNext { response ->
                    val latencyMs = (System.nanoTime() - started) / 1_000_000
                    log.debug("Bank HTTP response status=${response.statusCode().value()} latencyMs=${latencyMs} url=${request.url()}")
                }
                .doOnError { e ->
                    val latencyMs = (System.nanoTime() - started) / 1_000_000
                    log.warn("Bank HTTP failed latencyMs=${latencyMs} errorType=${e.javaClass.simpleName} message=${e.message} url=${request.url()}")
                }
                .doFinally {
                    restoreMdc(previous)
                }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(WebClientConfig::class.java)
    }
}
