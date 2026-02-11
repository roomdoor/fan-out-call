package com.example.loanlimit.service

import com.example.loanlimit.dto.LoanLimitQueryRequest
import com.example.loanlimit.dto.LoanLimitQueryResponse
import com.example.loanlimit.fanout.CoroutineLenderFanOutExecutor
import org.springframework.stereotype.Service

@Service
class CoroutineLoanLimitQueryService(
    private val loanLimitQueryOrchestrator: LoanLimitQueryOrchestrator,
    private val coroutineLenderFanOutExecutor: CoroutineLenderFanOutExecutor,
) {
    fun query(request: LoanLimitQueryRequest): LoanLimitQueryResponse {
        return loanLimitQueryOrchestrator.query(
            request = request,
            fanOutExecutor = coroutineLenderFanOutExecutor,
            modeName = "COROUTINE",
        )
    }
}
