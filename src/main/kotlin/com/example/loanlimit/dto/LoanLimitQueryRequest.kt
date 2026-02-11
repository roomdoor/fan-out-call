package com.example.loanlimit.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive

@Schema(description = "Loan-limit query request")
data class LoanLimitQueryRequest(
    @field:NotBlank
    @field:Schema(description = "Borrower unique identifier", example = "USER-1001")
    val borrowerId: String,
    @field:Positive
    @field:Schema(description = "Borrower annual income (KRW)", example = "70000000")
    val annualIncome: Long,
    @field:Positive
    @field:Schema(description = "Requested loan amount (KRW)", example = "30000000")
    val requestedAmount: Long,
)
