package com.example.loanlimit.service

import com.example.loanlimit.dto.LoanLimitQueryRequest
import com.example.loanlimit.dto.LoanLimitQueryResponse
import com.example.loanlimit.fanout.WebClientLenderFanOutExecutor
import org.springframework.stereotype.Service

@Service
class WebClientLoanLimitQueryService(
    private val loanLimitQueryOrchestrator: LoanLimitQueryOrchestrator,
    private val webClientLenderFanOutExecutor: WebClientLenderFanOutExecutor,
) {
    fun query(request: LoanLimitQueryRequest): LoanLimitQueryResponse {
        return loanLimitQueryOrchestrator.query(
            request = request,
            fanOutExecutor = webClientLenderFanOutExecutor,
            modeName = "WEBCLIENT_NON_BLOCKING",
        )
    }
}
