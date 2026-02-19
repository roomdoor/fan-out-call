package com.example.loanlimit.fanout.coroutine.controller

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
@RequestMapping("/api/v1/loan-limit/coroutine")
@Tag(name = "Loan Limit Coroutine", description = "Loan-limit submit endpoint (coroutine mode)")
class CoroutineLoanLimitQueryController(
    private val loanLimitQueryOrchestrator: LoanLimitQueryOrchestrator,
) {
    @PostMapping("/queries")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
        summary = "Start loan-limit fan-out query",
        description = "Creates a new transaction and starts bank fan-out asynchronously. Returns immediately with transaction identifiers.",
    )
    fun query(@Valid @RequestBody request: LoanLimitQueryRequest): LoanLimitQueryResponse {
        return loanLimitQueryOrchestrator.queryCoroutine(request)
    }
}
