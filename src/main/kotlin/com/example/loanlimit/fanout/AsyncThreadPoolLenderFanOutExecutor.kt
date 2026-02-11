package com.example.loanlimit.fanout

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.domain.LenderApiProfile
import com.example.loanlimit.dto.LoanLimitQueryRequest
import com.example.loanlimit.entity.LenderCallResultEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture

@Component
class AsyncThreadPoolLenderFanOutExecutor(
    private val appProperties: AppProperties,
    private val asyncLenderCallWorker: AsyncLenderCallWorker,
) : LenderFanOutExecutor {
    override suspend fun execute(
        runId: Long,
        lenders: List<LenderApiProfile>,
        request: LoanLimitQueryRequest,
        onEachResult: suspend (LenderCallResultEntity) -> Unit,
    ) {
        log.info(
            "Async-threadpool fan-out started runId=$runId lenderCount=${lenders.size} " +
                "corePoolSize=${appProperties.asyncThreadPool.corePoolSize} " +
                "maxPoolSize=${appProperties.asyncThreadPool.maxPoolSize} " +
                "queueCapacity=${appProperties.asyncThreadPool.queueCapacity} " +
                "perCallTimeoutMs=${appProperties.lenders.perCallTimeoutMs}",
        )

        withContext(Dispatchers.IO) {
            val completionFutures = lenders.map { lender ->
                asyncLenderCallWorker.call(runId, lender, request)
                    .thenAccept { result ->
                        runBlocking {
                            onEachResult(result)
                        }
                    }
            }

            CompletableFuture.allOf(*completionFutures.toTypedArray()).join()
        }

        log.info("Async-threadpool fan-out finished runId=$runId lenderCount=${lenders.size}")
    }

    companion object {
        private val log = LoggerFactory.getLogger(AsyncThreadPoolLenderFanOutExecutor::class.java)
    }
}
