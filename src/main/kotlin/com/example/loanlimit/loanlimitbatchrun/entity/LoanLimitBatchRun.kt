package com.example.loanlimit.loanlimitbatchrun.entity

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
class LoanLimitBatchRun(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null,

    @Column(name = "request_id", nullable = false, unique = true, length = 36)
    var requestId: String = "",

    @Column(name = "borrower_id", nullable = false, length = 64)
    var borrowerId: String = "",

    @Column(name = "requested_bank_count", nullable = false)
    var requestedBankCount: Int = 0,

    @Column(name = "success_count", nullable = false)
    var successCount: Int = 0,

    @Column(name = "failure_count", nullable = false)
    var failureCount: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: RunStatus = RunStatus.IN_PROGRESS,

    /**
     * 예외로 중단된 경우에만 채워진다. 은행을 다 호출하고 성공이 0이어서
     * FAILED 가 된 경우는 null — 둘 다 status 는 FAILED 라 이걸로 구분한다.
     */
    @Column(name = "fail_reason", length = 500)
    var failReason: String? = null,

    @Column(name = "started_at", nullable = false)
    var startedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "finished_at")
    var finishedAt: LocalDateTime? = null,
)
