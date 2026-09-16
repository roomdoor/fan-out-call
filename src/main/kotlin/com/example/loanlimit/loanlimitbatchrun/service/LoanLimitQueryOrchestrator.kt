package com.example.loanlimit.loanlimitbatchrun.service

import com.example.loanlimit.bankcallresult.service.BankCallResultService
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.loanlimitbatchrun.dto.response.LoanLimitQueryResponse
import com.example.loanlimit.fanout.BankFanOutExecutor
import com.example.loanlimit.fanout.BankFanOutExecutorRegistry
import com.example.loanlimit.bankcallresult.service.BankCatalogService
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
            // 실패한 은행은 행이 저장되지 않으므로 finalizeRunStatus가 세는
            // completedCount에서 빠지고, run은 PARTIAL_FAILURE가 된다. DB 쓰기 하나
            // 때문에 run 전체를 FAILED로 만드는 것보다 사실에 가깝다.
            val persistFailures = AtomicInteger()

            fanOutExecutor.execute(
                runId = runId,
                banks = banks,
                request = request,
            ) { result ->
                runCatching { bankCallResultService.persistResultWithRetry(result) }
                    .onFailure { e ->
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
        } catch (e: Exception) {
            log.error("Background fan-out failed", e)
            loanLimitBatchRunService.markRunFailed(runId, e.message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoanLimitQueryOrchestrator::class.java)
    }

}
