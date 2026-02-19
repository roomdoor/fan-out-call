package com.example.loanlimit.bankcallresult.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "bank_call_result")
class BankCallResult(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "run_id", nullable = false)
    var runId: Long = 0,

    @Column(name = "bank_code", nullable = false, length = 64)
    var bankCode: String = "",

    @Column(name = "host", nullable = false, length = 255)
    var host: String = "",

    @Column(name = "url", nullable = false, length = 255)
    var url: String = "",

    @Column(name = "http_status")
    var httpStatus: Int? = null,

    @Column(name = "success", nullable = false)
    var success: Boolean = false,

    @Column(name = "response_code", nullable = false, length = 32)
    var responseCode: String = "",

    @Column(name = "response_message", nullable = false, length = 255)
    var responseMessage: String = "",

    @Column(name = "approved_limit")
    var approvedLimit: Long? = null,

    @Column(name = "latency_ms", nullable = false)
    var latencyMs: Long = 0,

    @Column(name = "error_detail", length = 500)
    var errorDetail: String? = null,

    @Column(name = "request_payload", nullable = false, columnDefinition = "TEXT")
    var requestPayload: String = "",

    @Column(name = "response_payload", nullable = false, columnDefinition = "TEXT")
    var responsePayload: String = "",

    @Column(name = "requested_at", nullable = false)
    var requestedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "responded_at", nullable = false)
    var respondedAt: LocalDateTime = LocalDateTime.now(),
)
