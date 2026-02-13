package com.example.loanlimit.controller

import com.example.loanlimit.dto.LoanLimitQueryRequest
import com.example.loanlimit.dto.LoanLimitQueryResponse
import com.example.loanlimit.service.WebClientLoanLimitQueryService
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
@RequestMapping("/api/v1/loan-limit/webclient")
@Tag(name = "Loan Limit WebClient", description = "Loan-limit submit endpoint (WebClient non-blocking mode)")
class WebClientLoanLimitQueryController(
    private val webClientLoanLimitQueryService: WebClientLoanLimitQueryService,
) {
    @PostMapping("/queries")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
        summary = "Start loan-limit fan-out query (WebClient non-blocking mode)",
        description = "Creates a new transaction and starts lender fan-out using WebClient non-blocking HTTP calls.",
    )
    fun query(@Valid @RequestBody request: LoanLimitQueryRequest): LoanLimitQueryResponse {
        return webClientLoanLimitQueryService.query(request)
    }
}
