package com.example.loanlimit.bankcallresult.repository

import com.example.loanlimit.bankcallresult.entity.BankCallResult
import org.springframework.data.jpa.repository.JpaRepository

interface BankCallResultRepository : JpaRepository<BankCallResult, Long> {
    fun findAllByRunIdOrderByIdAsc(runId: Long): List<BankCallResult>
    fun countByRunId(runId: Long): Long
    fun countByRunIdAndSuccess(runId: Long, success: Boolean): Long
}
