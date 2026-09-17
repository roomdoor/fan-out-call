package com.example.loanlimit.loanlimitbatchrun.service

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.bankcallresult.repository.BankCallResultRepository
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.loanlimitbatchrun.dto.response.LoanLimitQueryResponse
import com.example.loanlimit.loanlimitbatchrun.entity.LoanLimitBatchRun
import com.example.loanlimit.loanlimitbatchrun.entity.RunStatus
import com.example.loanlimit.logging.MdcKeys
import com.example.loanlimit.loanlimitbatchrun.repository.LoanLimitBatchRunRepository
import org.slf4j.MDC
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

@Service
class LoanLimitBatchRunService(
    private val appProperties: AppProperties,
    private val batchRunRepository: LoanLimitBatchRunRepository,
    private val callResultRepository: BankCallResultRepository,
) {
    fun createRunAndSetMdc(
        request: LoanLimitQueryRequest,
        requestedBankCount: Int,
    ): LoanLimitBatchRun {
        val requestId = UUID.randomUUID().toString()
        val runEntity = batchRunRepository.save(
            LoanLimitBatchRun(
                requestId = requestId,
                borrowerId = request.borrowerId,
                requestedBankCount = requestedBankCount,
                status = RunStatus.IN_PROGRESS,
                startedAt = LocalDateTime.now(),
            ),
        )

        MDC.put(MdcKeys.BORROWER_ID, request.borrowerId)
        MDC.put(MdcKeys.RUN_ID, (runEntity.id ?: 0L).toString())

        return runEntity
    }

    fun getByRequestId(requestId: String): LoanLimitQueryResponse {
        val runEntity = batchRunRepository.findByRequestId(requestId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found: $requestId")
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

        log.info("Background fan-out completed status=$status completedCount=$completedCount successCount=$successCount failureCount=$failureCount")
    }

    fun markRunFailed(runId: Long, reason: String?) {
        val runEntity = batchRunRepository.findById(runId).orElse(null) ?: return
        val completedCount = callResultRepository.countByRunId(runId).toInt()
        val successCount = callResultRepository.countByRunIdAndSuccess(runId, true).toInt()
        val failureCount = completedCount - successCount

        runEntity.successCount = successCount
        runEntity.failureCount = failureCount
        runEntity.status = RunStatus.FAILED
        runEntity.failReason = truncateFailReason(reason ?: "unknown")
        runEntity.finishedAt = LocalDateTime.now()
        batchRunRepository.save(runEntity)

        log.warn("Run marked as FAILED completedCount=$completedCount successCount=$successCount failureCount=$failureCount reason=$reason")
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

        // loan_limit_batch_run.fail_reason 컬럼 길이
        const val FAIL_REASON_MAX = 500

        /**
         * 컬럼이 500자라 긴 예외 메시지를 그냥 넣으면 저장이 통째로 실패하고,
         * run이 FAILED로 표시조차 안 된다. 자르는 이유가 그것이다.
         *
         * 그냥 take(500)을 쓰면 서로게이트 쌍 가운데가 잘려 홀로 남을 수 있고,
         * MySQL이 그 문자열을 거부해서 막으려던 결과가 그대로 난다.
         */
        fun truncateFailReason(reason: String): String {
            if (reason.length <= FAIL_REASON_MAX) return reason
            val end =
                if (Character.isHighSurrogate(reason[FAIL_REASON_MAX - 1])) FAIL_REASON_MAX - 1
                else FAIL_REASON_MAX
            return reason.substring(0, end)
        }
    }
}
