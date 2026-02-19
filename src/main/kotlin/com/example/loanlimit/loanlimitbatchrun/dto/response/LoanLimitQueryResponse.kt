package com.example.loanlimit.loanlimitbatchrun.dto.response

import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.loanlimitbatchrun.entity.LoanLimitBatchRun
import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "Loan-limit query status/result response")
data class LoanLimitQueryResponse(
    @field:Schema(description = "Numeric transaction identifier", example = "101")
    val transactionNo: Long,
    @field:Schema(description = "UUID transaction identifier", example = "f6adff79-9be4-4d48-a6cc-4e87f5ff7fb7")
    val transactionId: String,
    @field:Schema(description = "Run status", example = "IN_PROGRESS")
    val status: String,
    @field:Schema(description = "Total bank APIs to process", example = "50")
    val requestedBankCount: Int,
    @field:Schema(description = "Completed success count", example = "12")
    val successCount: Int,
    @field:Schema(description = "Completed failure count", example = "3")
    val failureCount: Int,
    @field:Schema(description = "Completed count = successCount + failureCount", example = "15")
    val completedCount: Int,
    @field:Schema(description = "Elapsed milliseconds from startedAt to now/finishedAt", example = "8234")
    val elapsedMs: Long,
    @field:Schema(description = "True only when completed within requiredCompletionMs", example = "false")
    val completedWithinOneMinute: Boolean,
    val startedAt: LocalDateTime,
    val finishedAt: LocalDateTime?,
    val results: List<BankCallResultResponse>,
) {
    companion object {
        fun from(
            runEntity: LoanLimitBatchRun,
            results: List<BankCallResult> = emptyList(),
            successCount: Int = 0,
            failureCount: Int = 0,
            completedCount: Int = 0,
            elapsedMs: Long = 0,
            completedWithinOneMinute: Boolean = false,
        ): LoanLimitQueryResponse {
            return LoanLimitQueryResponse(
                transactionNo = runEntity.id ?: 0L,
                transactionId = runEntity.requestId,
                status = runEntity.status.name,
                requestedBankCount = runEntity.requestedBankCount,
                successCount = successCount,
                failureCount = failureCount,
                completedCount = completedCount,
                elapsedMs = elapsedMs,
                completedWithinOneMinute = completedWithinOneMinute,
                startedAt = runEntity.startedAt,
                finishedAt = runEntity.finishedAt,
                results = results.map {
                    BankCallResultResponse(
                        bankCode = it.bankCode,
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
    }
}

@Schema(description = "Per-bank result item")
data class BankCallResultResponse(
    val bankCode: String,
    val host: String,
    val url: String,
    val success: Boolean,
    val httpStatus: Int?,
    val responseCode: String,
    val responseMessage: String,
    val approvedLimit: Long?,
    val latencyMs: Long,
    val errorDetail: String?,
)
