package com.example.loanlimit.repository

import com.example.loanlimit.entity.LoanLimitBatchRunEntity
import org.springframework.data.jpa.repository.JpaRepository

interface LoanLimitBatchRunRepository : JpaRepository<LoanLimitBatchRunEntity, Long> {
    fun findByRequestId(requestId: String): LoanLimitBatchRunEntity?
}
