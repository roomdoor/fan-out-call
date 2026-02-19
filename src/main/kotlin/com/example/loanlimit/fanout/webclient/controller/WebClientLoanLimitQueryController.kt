package com.example.loanlimit.fanout.webclient.controller

import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.loanlimitbatchrun.dto.response.LoanLimitQueryResponse
import com.example.loanlimit.loanlimitbatchrun.service.LoanLimitQueryOrchestrator
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
    private val loanLimitQueryOrchestrator: LoanLimitQueryOrchestrator,
) {
    @PostMapping("/queries")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
        summary = "Start loan-limit fan-out query (WebClient non-blocking mode)",
        description = "Creates a new transaction and starts bank fan-out using WebClient non-blocking HTTP calls.",
    )
    fun query(@Valid @RequestBody request: LoanLimitQueryRequest): LoanLimitQueryResponse {
        return loanLimitQueryOrchestrator.queryWebClient(request)
    }
}
