package com.example.loanlimit.dto

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
    @field:Schema(description = "Total lender APIs to process", example = "50")
    val requestedLenderCount: Int,
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
    val results: List<LenderCallResultResponse>,
)

@Schema(description = "Per-lender result item")
data class LenderCallResultResponse(
    val lenderCode: String,
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
