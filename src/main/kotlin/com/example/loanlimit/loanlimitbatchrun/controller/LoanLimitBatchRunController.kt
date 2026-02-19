package com.example.loanlimit.loanlimitbatchrun.controller

import com.example.loanlimit.loanlimitbatchrun.dto.response.LoanLimitQueryResponse
import com.example.loanlimit.loanlimitbatchrun.service.LoanLimitBatchRunService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/loan-limit")
@Tag(name = "Loan Limit Polling", description = "Unified polling endpoints for all fan-out modes")
class LoanLimitBatchRunController(
    private val loanLimitBatchRunService: LoanLimitBatchRunService,
) {
    @GetMapping("/queries/{transactionId}")
    @Operation(summary = "Get progress/result by transactionId")
    fun getByTransactionId(
        @Parameter(description = "UUID transaction identifier from any mode submit endpoint")
        @PathVariable transactionId: String,
    ): LoanLimitQueryResponse {
        return loanLimitBatchRunService.getByTransactionId(transactionId)
    }

    @GetMapping("/queries/number/{transactionNo}")
    @Operation(summary = "Get progress/result by transactionNo")
    fun getByTransactionNo(
        @Parameter(description = "Numeric transaction identifier from any mode submit endpoint")
        @PathVariable transactionNo: Long,
    ): LoanLimitQueryResponse {
        return loanLimitBatchRunService.getByTransactionNo(transactionNo)
    }
}
