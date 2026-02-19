package com.example.loanlimit.loanlimitbatchrun.service

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.bankcallresult.repository.BankCallResultRepository
import com.example.loanlimit.loanlimitbatchrun.dto.response.LoanLimitQueryResponse
import com.example.loanlimit.loanlimitbatchrun.entity.LoanLimitBatchRun
import com.example.loanlimit.loanlimitbatchrun.entity.RunStatus
import com.example.loanlimit.loanlimitbatchrun.repository.LoanLimitBatchRunRepository
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDateTime

@Service
class LoanLimitBatchRunService(
    private val appProperties: AppProperties,
    private val batchRunRepository: LoanLimitBatchRunRepository,
    private val callResultRepository: BankCallResultRepository,
) {
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

    fun finalizeRunStatus(runId: Long) {
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

    fun markRunFailed(runId: Long, reason: String?) {
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

    private fun response(runEntity: LoanLimitBatchRun): LoanLimitQueryResponse {
        val callResults = callResultRepository.findAllByRunIdOrderByIdAsc(runEntity.id ?: 0L)
        val completedCount = callResults.size
        val successCount = callResults.count { it.success }
        val failureCount = completedCount - successCount

        val effectiveFinishedAt = runEntity.finishedAt ?: LocalDateTime.now()
        val elapsedMs = Duration.between(runEntity.startedAt, effectiveFinishedAt).toMillis()
        val completedWithinOneMinute =
            runEntity.finishedAt != null && elapsedMs <= appProperties.banks.requiredCompletionMs

        return LoanLimitQueryResponse.from(
            runEntity = runEntity,
            results = callResults,
            successCount = successCount,
            failureCount = failureCount,
            completedCount = completedCount,
            elapsedMs = elapsedMs,
            completedWithinOneMinute = completedWithinOneMinute,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(LoanLimitBatchRunService::class.java)
    }
}
