package com.example.loanlimit.bankcallresult.dto

data class MockExternalCallResult(
    val httpStatus: Int,
    val responseCode: String,
    val responseMessage: String,
    val approvedLimit: Long?,
    val requestPayload: String,
    val responsePayload: String,
)
