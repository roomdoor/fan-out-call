package com.example.loanlimit.repository

import com.example.loanlimit.entity.LenderCallResultEntity
import org.springframework.data.jpa.repository.JpaRepository

interface LenderCallResultRepository : JpaRepository<LenderCallResultEntity, Long> {
    fun findAllByRunIdOrderByIdAsc(runId: Long): List<LenderCallResultEntity>
    fun countByRunId(runId: Long): Long
    fun countByRunIdAndSuccess(runId: Long, success: Boolean): Long
}
