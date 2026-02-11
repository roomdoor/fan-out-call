package com.example.loanlimit.service

import com.example.loanlimit.dto.LoanLimitQueryResponse
import org.springframework.stereotype.Service

@Service
class LoanLimitPollingService(
    private val loanLimitQueryOrchestrator: LoanLimitQueryOrchestrator,
) {
    fun getByTransactionId(transactionId: String): LoanLimitQueryResponse {
        return loanLimitQueryOrchestrator.getByTransactionId(transactionId)
    }

    fun getByTransactionNo(transactionNo: Long): LoanLimitQueryResponse {
        return loanLimitQueryOrchestrator.getByTransactionNo(transactionNo)
    }
}
