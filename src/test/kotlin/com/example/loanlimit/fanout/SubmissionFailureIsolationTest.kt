package com.example.loanlimit.fanout

import com.example.loanlimit.bank.BankApiServiceRegistry
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.bankcallresult.service.BankCatalogService
import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.fanout.coroutine.CoroutineBankFanOutExecutor
import com.example.loanlimit.fanout.sequential.SequentialSingleThreadBankFanOutExecutor
import com.example.loanlimit.fanout.webclient.WebClientBankFanOutExecutor
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

/**
 * 제출 단계 실패(알 수 없는 은행 코드, 요청 직렬화 오류)가 그 은행에만
 * 갇히는지 고정한다.
 *
 * registry.get() 과 buildRequest() 가 try 밖에 있으면 모드마다 다른 방식으로
 * 전체가 무너진다 — coroutine은 형제 코루틴을 취소하고, sequential은 for
 * 루프가 멈춰 남은 은행을 호출조차 하지 않으며, webclient는 flatMap 매퍼에서
 * 던져 Flux 전체가 끝난다.
 *
 * 카탈로그에 없는 은행 코드를 섞어 registry.get() 이 던지게 만든다.
 * 나머지 은행은 mock 서버가 없어 호출 자체는 실패하지만, 결과는 기록되어야
 * 한다. 여기서 보는 것은 성공 여부가 아니라 "전부 결과가 오는가" 다.
 */
class SubmissionFailureIsolationTest {

    // BANK-01, BANK-02 는 카탈로그(count=2)에 있고 BANK-99 는 없다.
    private val banks = listOf("BANK-01", "BANK-99", "BANK-02")

    private val properties = AppProperties(
        banks = AppProperties.Banks(count = 2, parallelism = 3, perCallTimeoutMs = 300),
    )

    private val registry = BankApiServiceRegistry(
        bankCatalogService = BankCatalogService(properties),
        appProperties = properties,
        webClient = WebClient.builder().build(),
    )

    private val request = LoanLimitQueryRequest(
        borrowerId = "USER-1",
        annualIncome = 70_000_000,
        requestedAmount = 30_000_000,
    )

    @Test
    fun `coroutine - 알 수 없는 은행이 섞여도 나머지 은행의 결과가 모두 전달된다`() {
        val executor = CoroutineBankFanOutExecutor(properties, registry)

        assertEquals(banks.size, collect { onEach -> executor.execute(RUN_ID, banks, request, onEach) }.size)
    }

    @Test
    fun `sequential - 알 수 없는 은행에서 루프가 멈추지 않는다`() {
        val executor = SequentialSingleThreadBankFanOutExecutor(properties, registry)

        val results = collect { onEach -> executor.execute(RUN_ID, banks, request, onEach) }

        assertEquals(banks.size, results.size)
        // 루프가 중단됐다면 BANK-99 뒤의 은행이 빠진다
        assertEquals(banks.toSet(), results.map { it.bankCode }.toSet())
    }

    @Test
    fun `webclient - 알 수 없는 은행이 Flux 전체를 끝내지 않는다`() {
        val executor = WebClientBankFanOutExecutor(properties, WebClient.builder().build(), registry)

        assertEquals(banks.size, collect { onEach -> executor.execute(RUN_ID, banks, request, onEach) }.size)
    }

    private fun collect(
        run: suspend (suspend (BankCallResult) -> Unit) -> Unit,
    ): List<BankCallResult> {
        val collected = mutableListOf<BankCallResult>()
        runBlocking {
            run { result -> synchronized(collected) { collected += result } }
        }
        return collected
    }

    companion object {
        private const val RUN_ID = 1L
    }
}
