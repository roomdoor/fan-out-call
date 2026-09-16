package com.example.loanlimit.fanout.asyncpool

import com.example.loanlimit.bank.BankApiServiceRegistry
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.bankcallresult.service.BankCatalogService
import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import org.springframework.web.reactive.function.client.WebClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.util.concurrent.CompletableFuture
import java.util.concurrent.RejectedExecutionException

/**
 * 은행 하나의 제출 실패가 나머지 은행을 망가뜨리지 않는지 고정한다.
 *
 * 풀이 거부하면 @Async 프록시가 퓨처를 만들기 전에 동기적으로 던진다.
 * 그 예외가 coroutineScope로 올라가면 형제 코루틴이 전부 취소되어,
 * 이미 응답을 받아온 결과까지 버려진다.
 */
class AsyncThreadPoolBankFanOutExecutorTest {

    private val banks = listOf("BANK-01", "BANK-02", "BANK-03")

    // 실패 행 생성이 레지스트리로 옮겨졌다. 카탈로그에 이 은행들이 있어야
    // host가 샤드 기준으로 채워진다.
    private val registry = BankApiServiceRegistry(
        bankCatalogService = BankCatalogService(AppProperties()),
        appProperties = AppProperties(),
        webClient = WebClient.builder().build(),
    )
    private val request = LoanLimitQueryRequest(
        borrowerId = "USER-1",
        annualIncome = 70_000_000,
        requestedAmount = 30_000_000,
    )

    @Test
    fun `풀이 거부한 은행만 REJECTED로 기록되고 나머지는 정상 처리된다`() {
        val worker = StubWorker(failOn = "BANK-02", error = RejectedExecutionException("pool full"))
        val executor = AsyncThreadPoolBankFanOutExecutor(AppProperties(), worker, registry)

        val results = collect(executor)

        // 거부된 은행 때문에 형제가 취소되지 않았다
        assertEquals(banks.size, results.size)

        val rejected = results.single { it.bankCode == "BANK-02" }
        assertEquals("REJECTED", rejected.responseCode)
        assertEquals(false, rejected.success)

        val survivors = results.filter { it.bankCode != "BANK-02" }
        assertEquals(2, survivors.size)
        assertTrue(survivors.all { it.responseCode == "S000" })
    }

    @Test
    fun `거부가 아닌 제출 실패는 SUBMIT_ERROR로 구분된다`() {
        // 설정 오류(알 수 없는 은행 코드 등)를 풀 거부로 세면
        // 거부 카운트가 부하와 무관한 원인까지 포함하게 된다.
        // EXCEPTION은 AsyncBankCallWorker가 평범한 호출 실패에 쓰는 코드라
        // 그것도 쓰면 DB에서 구분되지 않는다.
        val worker = StubWorker(failOn = "BANK-03", error = IllegalStateException("unknown bank"))
        val executor = AsyncThreadPoolBankFanOutExecutor(AppProperties(), worker, registry)

        val results = collect(executor)

        assertEquals(banks.size, results.size)
        assertEquals("SUBMIT_ERROR", results.single { it.bankCode == "BANK-03" }.responseCode)
    }

    @Test
    fun `취소는 삼키지 않고 전파된다`() {
        // 취소를 실패 결과로 바꾸면 진행 중인 호출이 REJECTED 행으로 남고
        // 취소가 상위로 전달되지 않는다.
        val worker = StubWorker(failOn = "BANK-01", error = CancellationException("cancelled"))
        val executor = AsyncThreadPoolBankFanOutExecutor(AppProperties(), worker, registry)

        assertThrows<CancellationException> { collect(executor) }
    }

    @Test
    fun `모두 성공하면 은행 수만큼 결과가 전달된다`() {
        val executor = AsyncThreadPoolBankFanOutExecutor(AppProperties(), StubWorker(), registry)

        val results = collect(executor)

        assertEquals(banks.size, results.size)
        assertTrue(results.all { it.success })
    }

    private fun collect(executor: AsyncThreadPoolBankFanOutExecutor): List<BankCallResult> {
        val collected = mutableListOf<BankCallResult>()
        runBlocking {
            executor.execute(RUN_ID, banks, request) { result ->
                synchronized(collected) { collected += result }
            }
        }
        return collected
    }

    /**
     * 지정한 은행에서만 던지는 대역. 실제 워커는 @Async 프록시를 거치지만
     * 여기서 검증하려는 것은 executor가 그 예외를 어떻게 분류하는지다.
     */
    private class StubWorker(
        private val failOn: String? = null,
        private val error: Throwable? = null,
    ) : AsyncBankCallWorker(AppProperties(), STUB_REGISTRY) {

        override fun call(
            runId: Long,
            bankCode: String,
            request: LoanLimitQueryRequest,
        ): CompletableFuture<BankCallResult> {
            if (bankCode == failOn && error != null) {
                throw error
            }
            return CompletableFuture.completedFuture(successResult(runId, bankCode))
        }

        private fun successResult(runId: Long, bankCode: String): BankCallResult {
            val now = LocalDateTime.now()
            return BankCallResult(
                runId = runId,
                bankCode = bankCode,
                host = "http://localhost:18080",
                url = "/api/v1/mock-external/banks/$bankCode/loan-limit",
                httpStatus = 200,
                success = true,
                responseCode = "S000",
                responseMessage = "Approved",
                approvedLimit = 30_000_000,
                latencyMs = 10,
                errorDetail = null,
                requestPayload = "{}",
                responsePayload = "{}",
                requestedAt = now,
                respondedAt = now,
            )
        }

    }

    companion object {
        private const val RUN_ID = 1L

        // 대역은 super 생성자에만 필요하고 실제로 쓰지 않는다.
        private val STUB_REGISTRY = BankApiServiceRegistry(
            bankCatalogService = BankCatalogService(AppProperties()),
            appProperties = AppProperties(),
            webClient = WebClient.builder().build(),
        )
    }
}
