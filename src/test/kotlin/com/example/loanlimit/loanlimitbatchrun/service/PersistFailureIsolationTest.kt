package com.example.loanlimit.loanlimitbatchrun.service

import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.bankcallresult.service.BankCallResultService
import com.example.loanlimit.bankcallresult.service.BankCatalogService
import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.fanout.BankFanOutExecutor
import com.example.loanlimit.fanout.BankFanOutExecutorRegistry
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.loanlimitbatchrun.entity.LoanLimitBatchRun
import com.example.loanlimit.logging.MdcKeys
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.slf4j.MDC
import org.springframework.dao.DataAccessResourceFailureException
import java.time.LocalDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 은행 하나의 저장 실패가 나머지 은행의 저장을 막지 않는지 고정한다.
 *
 * 이 정책은 LoanLimitQueryOrchestrator 한 곳에 있다. executor마다 두면
 * 모드별로 어긋나는데, 실제로 그랬다 — coroutine은 형제를 취소하고,
 * sequential은 남은 은행을 호출조차 하지 않았으며, webclient는 Flux 전체를
 * 에러로 끝냈다. 네 모드가 서로 다른 규칙으로 실패하면 모드 간 비교가
 * 성립하지 않는다.
 */
class PersistFailureIsolationTest {

    private val banks = (1..5).map { "BANK-0$it" }

    // submit()이 요청의 borrowerId를 MDC 값과 대조한다. 평소에는
    // CorrelationContextFilter가 헤더에서 넣어주는 값이다.
    @BeforeEach
    fun setUpCorrelation() {
        MDC.put(MdcKeys.BORROWER_ID, BORROWER_ID)
    }

    @AfterEach
    fun clearCorrelation() {
        MDC.clear()
    }

    @Test
    fun `은행 하나의 저장이 실패해도 나머지 은행은 모두 저장을 시도한다`() {
        val attempted = mutableListOf<String>()
        val finished = CountDownLatch(1)

        val resultService = mock<BankCallResultService> {
            onBlocking { persistResultWithRetry(any()) } doAnswer { invocation ->
                val result = invocation.getArgument<BankCallResult>(0)
                synchronized(attempted) { attempted += result.bankCode }
                if (result.bankCode == "BANK-03") {
                    throw DataAccessResourceFailureException("connection pool exhausted")
                }
                Unit
            }
        }

        val orchestrator = orchestrator(resultService, finished)
        orchestrator.queryCoroutine(request())

        assertTrue(finished.await(10, TimeUnit.SECONDS), "백그라운드 fan-out이 끝나지 않았다")

        // 실패한 은행에서 멈추지 않고 전부 시도했다
        assertEquals(banks.size, attempted.size)
        assertEquals(banks.toSet(), attempted.toSet())
    }

    @Test
    fun `저장이 모두 실패해도 run은 마무리 단계까지 진행한다`() {
        val finished = CountDownLatch(1)

        val resultService = mock<BankCallResultService> {
            onBlocking { persistResultWithRetry(any()) } doAnswer {
                throw DataAccessResourceFailureException("db down")
            }
        }

        val orchestrator = orchestrator(resultService, finished)
        orchestrator.queryCoroutine(request())

        // finalizeRunStatus까지 도달해야 한다. 예외가 올라가면 markRunFailed로
        // 빠지면서 이 래치가 내려가지 않는다.
        assertTrue(finished.await(10, TimeUnit.SECONDS), "저장 실패가 run 전체를 중단시켰다")
    }

    private fun orchestrator(
        resultService: BankCallResultService,
        finished: CountDownLatch,
    ): LoanLimitQueryOrchestrator {
        val catalog = mock<BankCatalogService> { on { getAll() } doReturn banks }

        val runService = mock<LoanLimitBatchRunService>()
        whenever(runService.createRunAndSetMdc(any(), any())).thenReturn(runEntity())
        whenever(runService.finalizeRunStatus(any())).thenAnswer { finished.countDown() }

        val registry = mock<BankFanOutExecutorRegistry> {
            on { get(any()) } doReturn PassThroughExecutor()
        }

        return LoanLimitQueryOrchestrator(catalog, resultService, runService, registry)
    }

    private fun request() = LoanLimitQueryRequest(
        borrowerId = BORROWER_ID,
        annualIncome = 70_000_000,
        requestedAmount = 30_000_000,
    )

    private fun runEntity() = LoanLimitBatchRun(
        id = RUN_ID,
        requestId = "req-1",
        borrowerId = BORROWER_ID,
        requestedBankCount = banks.size,
    )

    /**
     * 모드별 동시성을 흉내내지 않는 최소 executor. 여기서 검증하려는 것은
     * onEachResult를 감싸는 쪽의 동작이지 executor의 병렬 구조가 아니다.
     */
    private inner class PassThroughExecutor : BankFanOutExecutor {
        override suspend fun execute(
            runId: Long,
            banks: List<String>,
            request: LoanLimitQueryRequest,
            onEachResult: suspend (BankCallResult) -> Unit,
        ) {
            banks.forEach { bank -> onEachResult(result(runId, bank)) }
        }

        private fun result(runId: Long, bankCode: String): BankCallResult {
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
        private const val BORROWER_ID = "USER-1"
    }
}
