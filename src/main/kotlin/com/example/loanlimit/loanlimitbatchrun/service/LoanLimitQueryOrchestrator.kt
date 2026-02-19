package com.example.loanlimit.loanlimitbatchrun.service

import com.example.loanlimit.bankcallresult.service.BankCallResultService
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.loanlimitbatchrun.dto.response.LoanLimitQueryResponse
import com.example.loanlimit.loanlimitbatchrun.entity.LoanLimitBatchRun
import com.example.loanlimit.loanlimitbatchrun.entity.RunStatus
import com.example.loanlimit.fanout.BankFanOutExecutor
import com.example.loanlimit.fanout.BankFanOutExecutorRegistry
import com.example.loanlimit.bankcallresult.service.BankCatalogService
import com.example.loanlimit.loanlimitbatchrun.repository.LoanLimitBatchRunRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

@Service
class LoanLimitQueryOrchestrator(
    private val bankCatalogService: BankCatalogService,
    private val batchRunRepository: LoanLimitBatchRunRepository,
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
        val fanOutExecutor = bankFanOutExecutorRegistry.get(modeName)
        val banks = bankCatalogService.getAll()
        val runEntity = batchRunRepository.save(
            LoanLimitBatchRun(
                requestId = UUID.randomUUID().toString(),
                borrowerId = request.borrowerId,
                requestedBankCount = banks.size,
                status = RunStatus.IN_PROGRESS,
                startedAt = LocalDateTime.now(),
            ),
        )

        val runId = runEntity.id ?: 0L
        log.info("Loan-limit query accepted runId=$runId transactionId=${runEntity.requestId} borrowerId=${request.borrowerId} bankCount=${banks.size} mode=$modeName")

        backgroundScope.launch {
            processInBackground(
                runId = runId,
                banks = banks,
                request = request,
                fanOutExecutor = fanOutExecutor,
                modeName = modeName,
            )
        }

        return LoanLimitQueryResponse.from(runEntity = runEntity)
    }

    private suspend fun processInBackground(
        runId: Long,
        banks: List<String>,
        request: LoanLimitQueryRequest,
        fanOutExecutor: BankFanOutExecutor,
        modeName: String,
    ) {
        try {
            log.info("Background fan-out started runId=$runId borrowerId=${request.borrowerId} bankCount=${banks.size} mode=$modeName")

            fanOutExecutor.execute(
                runId = runId,
                banks = banks,
                request = request,
            ) { result ->
                bankCallResultService.persistResultWithRetry(result)
            }

            loanLimitBatchRunService.finalizeRunStatus(runId)
        } catch (e: Exception) {
            log.error("Background fan-out failed runId=$runId", e)
            loanLimitBatchRunService.markRunFailed(runId, e.message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoanLimitQueryOrchestrator::class.java)
    }

}
