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

            // 저장 실패를 은행 단위로 가둔다. 여기서 예외가 밖으로 나가면 모드마다
            // 다른 방식으로 나머지 은행을 망가뜨린다 — coroutine은 형제 코루틴을
            // 취소하고, sequential은 남은 은행을 호출조차 하지 않으며, webclient는
            // Flux 전체를 에러로 끝낸다. onEachResult가 정의되는 곳이 여기 한 곳뿐이라
            // 여기서 막으면 네 모드의 동작이 같아진다.
            //
            // 주의 — 저장에 실패한 은행은 행 자체가 없다. finalizeRunStatus는
            // 저장된 행만 세고 requestedBankCount와 비교하지 않으므로, 남은 행이
            // 전부 성공이면 run은 COMPLETED가 된다. 50개 중 47개만 저장돼도
            // COMPLETED다. 부분 성공을 따로 구분하지 않기로 한 결정이다.
            //
            // 그래서 저장 실패는 상태가 아니라 로그로만 드러난다. 아래 집계 로그와
            // parse.mjs의 경고가 유일한 신호다. 저장 실패가 있었던 회차는 실효
            // 처리율이 실제보다 높게 나오므로 처리량 비교에 쓰면 안 된다.
            val persistFailures = AtomicInteger()

            fanOutExecutor.execute(
                runId = runId,
                banks = banks,
                request = request,
            ) { result ->
                try {
                    bankCallResultService.persistResultWithRetry(result)
                } catch (e: CancellationException) {
                    // 취소는 저장 실패가 아니다. 삼키면 persist_failures가 부풀어
                    // "DB가 병목이었다"는 잘못된 신호가 되고, 취소가 전파되지 않아
                    // sequential 모드는 남은 은행을 끝까지 호출하게 된다.
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

            loanLimitBatchRunService.finalizeRunStatus(runId)
        } catch (e: CancellationException) {
            // CancellationException은 IllegalStateException을 상속하므로 아래
            // catch(Exception)에 걸린다. 그대로 두면 이미 취소된 코루틴에서
            // markRunFailed가 블로킹 JPA 읽기·쓰기를 하고, run_errors 수치가
            // 부풀며, 취소는 여전히 Job에 도달하지 못한다.
            throw e
        } catch (e: Exception) {
            log.error("Background fan-out failed", e)
            loanLimitBatchRunService.markRunFailed(runId, e.message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoanLimitQueryOrchestrator::class.java)
    }

}
