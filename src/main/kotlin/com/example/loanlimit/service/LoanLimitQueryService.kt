package com.example.loanlimit.service

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.domain.LenderApiProfile
import com.example.loanlimit.dto.LenderCallResultResponse
import com.example.loanlimit.dto.LoanLimitQueryRequest
import com.example.loanlimit.dto.LoanLimitQueryResponse
import com.example.loanlimit.entity.LenderCallResultEntity
import com.example.loanlimit.entity.LoanLimitBatchRunEntity
import com.example.loanlimit.entity.RunStatus
import com.example.loanlimit.fanout.LenderFanOutExecutor
import com.example.loanlimit.repository.LenderCallResultRepository
import com.example.loanlimit.repository.LoanLimitBatchRunRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.HttpStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

@Service
class LoanLimitQueryService(
    private val appProperties: AppProperties,
    private val lenderCatalogService: LenderCatalogService,
    private val lenderFanOutExecutor: LenderFanOutExecutor,
    private val batchRunRepository: LoanLimitBatchRunRepository,
    private val callResultRepository: LenderCallResultRepository,
    private val runProgressTransactionService: RunProgressTransactionService,
) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun query(request: LoanLimitQueryRequest): LoanLimitQueryResponse {
        val lenders = lenderCatalogService.getAll()
        val runEntity = batchRunRepository.save(
            LoanLimitBatchRunEntity(
                requestId = UUID.randomUUID().toString(),
                borrowerId = request.borrowerId,
                requestedLenderCount = lenders.size,
                status = RunStatus.IN_PROGRESS,
                startedAt = LocalDateTime.now(),
            ),
        )

        val runId = runEntity.id ?: 0L
        log.info(
            "Loan-limit query accepted runId=$runId transactionId=${runEntity.requestId} " +
                "borrowerId=${request.borrowerId} lenderCount=${lenders.size}",
        )

        backgroundScope.launch {
            processInBackground(
                runId = runId,
                lenders = lenders,
                request = request,
            )
        }

        return toResponse(
            runEntity = runEntity,
            results = emptyList(),
            successCount = 0,
            failureCount = 0,
            completedCount = 0,
            elapsedMs = 0,
            completedWithinOneMinute = false,
        )
    }

    fun getByTransactionId(transactionId: String): LoanLimitQueryResponse {
        val runEntity = batchRunRepository.findByRequestId(transactionId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found: $transactionId")
        return response(runEntity)
    }

    fun getByTransactionNo(transactionNo: Long): LoanLimitQueryResponse {
        val runEntity = batchRunRepository.findById(transactionNo).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found: $transactionNo")
        }
        return response(runEntity)
    }

    private fun response(runEntity: LoanLimitBatchRunEntity): LoanLimitQueryResponse {
        val callResults = callResultRepository.findAllByRunIdOrderByIdAsc(runEntity.id ?: 0L)
        val completedCount = callResults.size
        val successCount = callResults.count { it.success }
        val failureCount = completedCount - successCount

        val effectiveFinishedAt = runEntity.finishedAt ?: LocalDateTime.now()
        val elapsedMs = Duration.between(runEntity.startedAt, effectiveFinishedAt).toMillis()
        val completedWithinOneMinute =
            runEntity.finishedAt != null && elapsedMs <= appProperties.lenders.requiredCompletionMs

        return toResponse(
            runEntity = runEntity,
            results = callResults,
            successCount = successCount,
            failureCount = failureCount,
            completedCount = completedCount,
            elapsedMs = elapsedMs,
            completedWithinOneMinute = completedWithinOneMinute,
        )
    }

    private suspend fun processInBackground(
        runId: Long,
        lenders: List<LenderApiProfile>,
        request: LoanLimitQueryRequest,
    ) {
        try {
            log.info(
                "Background fan-out started runId=$runId borrowerId=${request.borrowerId} lenderCount=${lenders.size}",
            )

            lenderFanOutExecutor.execute(
                runId = runId,
                lenders = lenders,
                request = request,
            ) { result ->
                persistResultWithRetry(result)
            }

            finalizeRunStatus(runId)
        } catch (e: Exception) {
            log.error("Background fan-out failed runId=$runId", e)
            markRunFailed(runId, e.message)
        }
    }

    private fun finalizeRunStatus(runId: Long) {
        val runEntity = batchRunRepository.findById(runId).orElse(null) ?: return
        val completedCount = callResultRepository.countByRunId(runId).toInt()
        val successCount = callResultRepository.countByRunIdAndSuccess(runId, true).toInt()
        val failureCount = completedCount - successCount

        val status = when {
            completedCount == 0 -> RunStatus.FAILED
            failureCount == 0 -> RunStatus.COMPLETED
            successCount == 0 -> RunStatus.FAILED
            else -> RunStatus.PARTIAL_FAILURE
        }

        runEntity.successCount = successCount
        runEntity.failureCount = failureCount
        runEntity.status = status
        runEntity.finishedAt = LocalDateTime.now()
        batchRunRepository.save(runEntity)

        log.info(
            "Background fan-out completed runId=$runId status=$status completedCount=$completedCount " +
                "successCount=$successCount failureCount=$failureCount",
        )
    }

    private fun markRunFailed(runId: Long, reason: String?) {
        val runEntity = batchRunRepository.findById(runId).orElse(null) ?: return
        val completedCount = callResultRepository.countByRunId(runId).toInt()
        val successCount = callResultRepository.countByRunIdAndSuccess(runId, true).toInt()
        val failureCount = completedCount - successCount

        runEntity.successCount = successCount
        runEntity.failureCount = failureCount
        runEntity.status = RunStatus.FAILED
        runEntity.finishedAt = LocalDateTime.now()
        batchRunRepository.save(runEntity)

        log.warn(
            "Run marked as FAILED runId=$runId completedCount=$completedCount successCount=$successCount " +
                "failureCount=$failureCount reason=$reason",
        )
    }

    private suspend fun persistResultWithRetry(
        result: LenderCallResultEntity,
    ) {
        var attempt = 1
        while (true) {
            try {
                runProgressTransactionService.saveResult(result)
                if (attempt > 1) {
                    log.info(
                        "Result persist recovered after retry runId=${result.runId} lenderCode=${result.lenderCode} " +
                            "attempt=$attempt",
                    )
                }
                return
            } catch (e: TransientDataAccessException) {
                log.warn(
                    "Transient DB error while persisting result runId=${result.runId} lenderCode=${result.lenderCode} " +
                        "attempt=$attempt maxAttempts=$MAX_PERSIST_RETRY_ATTEMPTS",
                    e,
                )
                if (attempt >= MAX_PERSIST_RETRY_ATTEMPTS) {
                    log.error(
                        "Result persist retry exhausted runId=${result.runId} lenderCode=${result.lenderCode} " +
                            "attempts=$attempt",
                        e,
                    )
                    throw e
                }
                delay(RETRY_BACKOFF_MS * attempt)
                attempt += 1
            }
        }
    }

    private fun toResponse(
        runEntity: LoanLimitBatchRunEntity,
        results: List<LenderCallResultEntity>,
        successCount: Int,
        failureCount: Int,
        completedCount: Int,
        elapsedMs: Long,
        completedWithinOneMinute: Boolean,
    ): LoanLimitQueryResponse {
        return LoanLimitQueryResponse(
            transactionNo = runEntity.id ?: 0L,
            transactionId = runEntity.requestId,
            status = runEntity.status.name,
            requestedLenderCount = runEntity.requestedLenderCount,
            successCount = successCount,
            failureCount = failureCount,
            completedCount = completedCount,
            elapsedMs = elapsedMs,
            completedWithinOneMinute = completedWithinOneMinute,
            startedAt = runEntity.startedAt,
            finishedAt = runEntity.finishedAt,
            results = results.map {
                LenderCallResultResponse(
                    lenderCode = it.lenderCode,
                    host = it.host,
                    url = it.url,
                    success = it.success,
                    httpStatus = it.httpStatus,
                    responseCode = it.responseCode,
                    responseMessage = it.responseMessage,
                    approvedLimit = it.approvedLimit,
                    latencyMs = it.latencyMs,
                    errorDetail = it.errorDetail,
                )
            },
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoanLimitQueryService::class.java)
        private const val MAX_PERSIST_RETRY_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 25L
    }

}
