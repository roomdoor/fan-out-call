package com.example.loanlimit.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "loan_limit_batch_run")
class LoanLimitBatchRunEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "request_id", nullable = false, unique = true, length = 36)
    var requestId: String = "",

    @Column(name = "borrower_id", nullable = false, length = 64)
    var borrowerId: String = "",

    @Column(name = "requested_lender_count", nullable = false)
    var requestedLenderCount: Int = 0,

    @Column(name = "success_count", nullable = false)
    var successCount: Int = 0,

    @Column(name = "failure_count", nullable = false)
    var failureCount: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: RunStatus = RunStatus.IN_PROGRESS,

    @Column(name = "started_at", nullable = false)
    var startedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "finished_at")
    var finishedAt: LocalDateTime? = null,
)
