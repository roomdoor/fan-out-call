package com.example.loanlimit.bankcallresult.service

import com.example.loanlimit.bankcallresult.entity.BankCallResult
import com.example.loanlimit.bankcallresult.repository.BankCallResultRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.dao.TransientDataAccessException
import org.springframework.stereotype.Service

@Service
class BankCallResultService(
    private val callResultRepository: BankCallResultRepository,
) {
    suspend fun persistResultWithRetry(result: BankCallResult) {
        var attempt = 1
        while (true) {
            try {
                withContext(Dispatchers.IO) {
                    callResultRepository.save(result)
                }
                if (attempt > 1) {
                    log.info(
                        "Result persist recovered after retry runId=${result.runId} bankCode=${result.bankCode} " +
                            "attempt=$attempt",
                    )
                }
                return
            } catch (e: TransientDataAccessException) {
                log.warn(
                    "Transient DB error while persisting result runId=${result.runId} bankCode=${result.bankCode} " +
                        "attempt=$attempt maxAttempts=$MAX_PERSIST_RETRY_ATTEMPTS",
                    e,
                )
                if (attempt >= MAX_PERSIST_RETRY_ATTEMPTS) {
                    log.error(
                        "Result persist retry exhausted runId=${result.runId} bankCode=${result.bankCode} " +
                            "attempts=$attempt",
                        e,
                    )
                    throw e
                }
                delay(RETRY_BACKOFF_MS * attempt)
                attempt += 1
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(BankCallResultService::class.java)
        private const val MAX_PERSIST_RETRY_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 25L
    }
}
