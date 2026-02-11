package com.example.loanlimit.service

import com.example.loanlimit.dto.LoanLimitQueryRequest
import com.example.loanlimit.dto.LoanLimitQueryResponse
import com.example.loanlimit.fanout.SequentialSingleThreadLenderFanOutExecutor
import org.springframework.stereotype.Service

@Service
class SequentialLoanLimitQueryService(
    private val loanLimitQueryOrchestrator: LoanLimitQueryOrchestrator,
    private val sequentialSingleThreadLenderFanOutExecutor: SequentialSingleThreadLenderFanOutExecutor,
) {
    fun query(request: LoanLimitQueryRequest): LoanLimitQueryResponse {
        return loanLimitQueryOrchestrator.query(
            request = request,
            fanOutExecutor = sequentialSingleThreadLenderFanOutExecutor,
            modeName = "SEQUENTIAL_SINGLE_THREAD",
        )
    }
}
