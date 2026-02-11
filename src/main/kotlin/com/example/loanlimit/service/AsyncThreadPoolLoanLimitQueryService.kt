package com.example.loanlimit.service

import com.example.loanlimit.dto.LoanLimitQueryRequest
import com.example.loanlimit.dto.LoanLimitQueryResponse
import com.example.loanlimit.fanout.AsyncThreadPoolLenderFanOutExecutor
import org.springframework.stereotype.Service

@Service
class AsyncThreadPoolLoanLimitQueryService(
    private val loanLimitQueryOrchestrator: LoanLimitQueryOrchestrator,
    private val asyncThreadPoolLenderFanOutExecutor: AsyncThreadPoolLenderFanOutExecutor,
) {
    fun query(request: LoanLimitQueryRequest): LoanLimitQueryResponse {
        return loanLimitQueryOrchestrator.query(
            request = request,
            fanOutExecutor = asyncThreadPoolLenderFanOutExecutor,
            modeName = "ASYNC_THREADPOOL",
        )
    }
}
