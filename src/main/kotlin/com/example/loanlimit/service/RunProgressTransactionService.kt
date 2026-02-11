package com.example.loanlimit.service

import com.example.loanlimit.entity.LenderCallResultEntity
import com.example.loanlimit.repository.LenderCallResultRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class RunProgressTransactionService(
    private val callResultRepository: LenderCallResultRepository,
) {
    @Transactional
    fun saveResult(result: LenderCallResultEntity) {
        callResultRepository.save(result)
    }
}
