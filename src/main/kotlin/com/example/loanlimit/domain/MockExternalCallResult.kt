package com.example.loanlimit.domain

data class MockExternalCallResult(
    val httpStatus: Int,
    val responseCode: String,
    val responseMessage: String,
    val approvedLimit: Long?,
    val requestPayload: String,
    val responsePayload: String,
)
