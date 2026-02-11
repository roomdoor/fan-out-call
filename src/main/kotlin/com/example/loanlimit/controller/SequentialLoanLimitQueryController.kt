package com.example.loanlimit.controller

import com.example.loanlimit.dto.LoanLimitQueryRequest
import com.example.loanlimit.dto.LoanLimitQueryResponse
import com.example.loanlimit.service.SequentialLoanLimitQueryService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/loan-limit/sequential")
@Tag(name = "Loan Limit Sequential", description = "Loan-limit submit endpoint (single-thread sequential mode)")
class SequentialLoanLimitQueryController(
    private val sequentialLoanLimitQueryService: SequentialLoanLimitQueryService,
) {
    @PostMapping("/queries")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
        summary = "Start loan-limit fan-out query (sequential mode)",
        description = "Creates a new transaction and starts lender fan-out asynchronously with single-thread sequential execution.",
    )
    fun query(@Valid @RequestBody request: LoanLimitQueryRequest): LoanLimitQueryResponse {
        return sequentialLoanLimitQueryService.query(request)
    }
}
