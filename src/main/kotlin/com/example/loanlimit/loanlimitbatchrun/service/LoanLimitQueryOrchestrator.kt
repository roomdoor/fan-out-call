package com.example.loanlimit.loanlimitbatchrun.service

import com.example.loanlimit.bankcallresult.service.BankCallResultService
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.loanlimitbatchrun.dto.response.LoanLimitQueryResponse
import com.example.loanlimit.fanout.BankFanOutExecutor
import com.example.loanlimit.fanout.BankFanOutExecutorRegistry
import com.example.loanlimit.bankcallresult.service.BankCatalogService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import com.example.loanlimit.logging.MdcKeys
import com.example.loanlimit.logging.restoreMdc
import java.util.concurrent.atomic.AtomicInteger

@Service
class LoanLimitQueryOrchestrator(
    private val bankCatalogService: BankCatalogService,
    private val bankCallResultService: BankCallResultService,
    private val loanLimitBatchRunService: LoanLimitBatchRunService,
    private val bankFanOutExecutorRegistry: BankFanOutExecutorRegistry,
) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun queryCoroutine(request: LoanLimitQueryRequest): LoanLimitQueryResponse {
        return submit(request, BankFanOutExecutorRegistry.MODE_COROUTINE)
    }

    fun querySequential(request: LoanLimitQueryRequest): LoanLimitQueryResponse {
        return submit(request, BankFanOutExecutorRegistry.MODE_SEQUENTIAL_SINGLE_THREAD)
    }

    fun queryAsyncThreadPool(request: LoanLimitQueryRequest): LoanLimitQueryResponse {
        return submit(request, BankFanOutExecutorRegistry.MODE_ASYNC_THREADPOOL)
    }

    fun queryWebClient(request: LoanLimitQueryRequest): LoanLimitQueryResponse {
        return submit(request, BankFanOutExecutorRegistry.MODE_WEBCLIENT_NON_BLOCKING)
    }

    private fun submit(
        request: LoanLimitQueryRequest,
        modeName: String,
    ): LoanLimitQueryResponse {
        validateBorrowerId(request)

        val prevMdc = MDC.getCopyOfContextMap()

        val fanOutExecutor = bankFanOutExecutorRegistry.get(modeName)
        val banks = bankCatalogService.getAll()
        val runEntity = loanLimitBatchRunService.createRunAndSetMdc(
            request = request,
            requestedBankCount = banks.size,
        )

        val runId = runEntity.id ?: 0L
        log.info("Loan-limit query accepted bankCount=${banks.size} mode=$modeName")

        backgroundScope.launch(MDCContext()) {
            processInBackground(
                runId = runId,
                banks = banks,
                request = request,
                fanOutExecutor = fanOutExecutor,
                modeName = modeName,
            )
        }

        return try {
            LoanLimitQueryResponse.from(runEntity = runEntity)
        } finally {
            restoreMdc(prevMdc)
        }
    }

    private fun validateBorrowerId(request: LoanLimitQueryRequest) {
        val borrowerIdFromHeader = MDC.get(MdcKeys.BORROWER_ID)
        if (borrowerIdFromHeader != request.borrowerId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "borrowerId mismatch")
        }
    }

    private suspend fun processInBackground(
        runId: Long,
        banks: List<String>,
        request: LoanLimitQueryRequest,
        fanOutExecutor: BankFanOutExecutor,
        modeName: String,
    ) {
        try {
            log.info("Background fan-out started bankCount=${banks.size} mode=$modeName")

            // 저장 실패를 은행 단위로 가둔다. onEachResult가 정의되는 곳이
            // 여기뿐이라 여기서 막아야 네 모드가 같게 동작한다.
            //
            // 실패한 은행은 행이 없는데 finalizeRunStatus는 저장된 행만 세므로
            // run은 COMPLETED로 남는다(부분 성공을 구분하지 않기로 한 결정).
            // 그래서 아래 집계 로그와 그걸 읽는 parse.mjs 경고로만 드러난다.
            val persistFailures = AtomicInteger()

            fanOutExecutor.execute(
                runId = runId,
                banks = banks,
                request = request,
            ) { result ->
                try {
                    bankCallResultService.persistResultWithRetry(result)
                } catch (e: CancellationException) {
                    // 취소는 저장 실패가 아니다. 삼키면 집계가 부풀고 취소도 안 퍼진다.
                    throw e
                } catch (e: Exception) {
                    persistFailures.incrementAndGet()
                    log.error("Result persistence failed bankCode=${result.bankCode}", e)
                }
            }

            val failedPersists = persistFailures.get()
            if (failedPersists > 0) {
                log.error(
                    "Result persistence failed for $failedPersists/${banks.size} banks. " +
                        "Run status counts only persisted results.",
                )
            }

            // 집계는 따로 가둔다. 여기서 터지는 건(커넥션 풀 고갈, 락 대기)
            // fan-out 코드 문제가 아니라 부하 증상이라 따로 기록해야 한다.
            try {
                loanLimitBatchRunService.finalizeRunStatus(runId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("Run status finalization failed", e)
                // markRunFailed 도 커넥션을 쓴다. 풀이 마른 상황이면 이것도 터지는데,
                // 밖으로 새면 아래 catch 가 접두사 없이 다시 기록해서 부하 증상이
                // 코드 문제로 뒤바뀐다. 못 남기면 IN_PROGRESS 로 두는 편이 낫다.
                try {
                    loanLimitBatchRunService.markRunFailed(runId, "$FINALIZE_FAILED_PREFIX${e.message}")
                } catch (cancelled: CancellationException) {
                    // CancellationException 은 IllegalStateException 을 상속한다.
                    // 아래로 넘기면 종료 중 취소가 정상 완료로 보고된다.
                    throw cancelled
                } catch (ignored: Exception) {
                    log.error("Could not mark the run as FAILED after finalization failure", ignored)
                }
            }
        } catch (e: CancellationException) {
            // 아래 catch(Exception)이 이걸 잡아버린다(IllegalStateException 상속).
            // 그러면 취소된 코루틴에서 markRunFailed가 블로킹 JPA를 돌린다.
            throw e
        } catch (e: Exception) {
            log.error("Background fan-out failed", e)
            loanLimitBatchRunService.markRunFailed(runId, e.message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoanLimitQueryOrchestrator::class.java)

        /**
         * 집계 단계에서 터진 run 을 fan-out 이 터진 run 과 구분하는 표시.
         * 측정 스크립트가 fail_reason 으로 이 둘을 갈라 센다.
         */
        const val FINALIZE_FAILED_PREFIX = "FINALIZE_FAILED: "
    }

}
