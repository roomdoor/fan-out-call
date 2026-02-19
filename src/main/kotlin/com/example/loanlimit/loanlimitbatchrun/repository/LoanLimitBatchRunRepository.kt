package com.example.loanlimit.loanlimitbatchrun.repository

import com.example.loanlimit.loanlimitbatchrun.entity.LoanLimitBatchRun
import org.springframework.data.jpa.repository.JpaRepository

interface LoanLimitBatchRunRepository : JpaRepository<LoanLimitBatchRun, Long> {
    fun findByRequestId(requestId: String): LoanLimitBatchRun?
}
