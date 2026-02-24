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

            fanOutExecutor.execute(
                runId = runId,
                banks = banks,
                request = request,
            ) { result ->
                bankCallResultService.persistResultWithRetry(result)
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
