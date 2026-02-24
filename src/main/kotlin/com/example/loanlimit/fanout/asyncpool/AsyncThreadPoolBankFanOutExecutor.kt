package com.example.loanlimit.fanout.asyncpool

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.fanout.BankFanOutExecutor
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

@Component
class AsyncThreadPoolBankFanOutExecutor(
    private val appProperties: AppProperties,
    private val asyncBankCallWorker: AsyncBankCallWorker,
) : BankFanOutExecutor {
    override suspend fun execute(
        runId: Long,
        banks: List<String>,
        request: LoanLimitQueryRequest,
        onEachResult: suspend (BankCallResult) -> Unit,
    ) {
        log.info(
            "Async-threadpool fan-out started bankCount=${banks.size} " +
                "corePoolSize=${appProperties.asyncThreadPool.corePoolSize} " +
                "maxPoolSize=${appProperties.asyncThreadPool.maxPoolSize} " +
                "queueCapacity=${appProperties.asyncThreadPool.queueCapacity} " +
                "perCallTimeoutMs=${appProperties.banks.perCallTimeoutMs}",
        )

        withContext(Dispatchers.IO) {
            val completionFutures = banks.map { bank ->
                asyncBankCallWorker.call(runId, bank, request)
                    .thenAccept { result ->
                        runBlocking {
                            onEachResult(result)
                        }
                    }
            }

            CompletableFuture.allOf(*completionFutures.toTypedArray()).join()
        }

        log.info("Async-threadpool fan-out finished bankCount=${banks.size}")
    }

    companion object {
        private val log = LoggerFactory.getLogger(AsyncThreadPoolBankFanOutExecutor::class.java)
    }
}
