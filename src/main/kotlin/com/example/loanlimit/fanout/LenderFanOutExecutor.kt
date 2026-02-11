package com.example.loanlimit.fanout

import com.example.loanlimit.domain.LenderApiProfile
import com.example.loanlimit.dto.LoanLimitQueryRequest
import com.example.loanlimit.entity.LenderCallResultEntity

interface LenderFanOutExecutor {
    suspend fun execute(
        runId: Long,
        lenders: List<LenderApiProfile>,
        request: LoanLimitQueryRequest,
        onEachResult: suspend (LenderCallResultEntity) -> Unit,
    )
}
