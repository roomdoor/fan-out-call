package com.example.loanlimit.fanout.asyncpool

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.fanout.BankFanOutExecutor
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

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

        // 은행 호출 자체는 여전히 블로킹이다. AsyncBankCallWorker가 @Async 풀
        // 스레드에서 응답까지 스레드를 점유하는 구조는 이 모드의 본질이라 그대로 둔다.
        //
        // 바뀐 것은 완료를 기다리는 방식이다. 이전에는 allOf().join() 으로
        // Dispatchers.IO 워커 하나를 run이 끝날 때까지(~31초) 하드 블로킹했다.
        // 결과 저장(persistResultWithRetry)도 같은 IO 디스패처를 필요로 하므로,
        // 동시 run이 IO 워커 수(기본 64)를 넘으면 저장할 워커가 남지 않아
        // join()이 영원히 반환되지 않는 교착이 발생했다. 64 / 31s 는 약 124 RPM 이다.
        //
        // await()는 서스펜드라 대기 중에 워커를 반납한다.
        coroutineScope {
            banks.map { bank ->
                async {
                    val result = asyncBankCallWorker.call(runId, bank, request).await()
                    onEachResult(result)
                }
            }.awaitAll()
        }

        log.info("Async-threadpool fan-out finished bankCount=${banks.size}")
    }

    companion object {
        private val log = LoggerFactory.getLogger(AsyncThreadPoolBankFanOutExecutor::class.java)
    }
}
