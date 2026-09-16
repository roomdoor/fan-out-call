package com.example.loanlimit.fanout.asyncpool

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.fanout.BankFanOutExecutor
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import kotlinx.coroutines.Dispatchers
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
        // 저장 실패를 은행 단위로 가둔다. 그냥 던지면 coroutineScope가 형제
        // 코루틴을 취소해서, 이미 받아온 나머지 49건이 저장되지 못하고 버려진다.
        // 옛 allOf().join() 은 각 thenAccept가 독립이라 전부 저장한 뒤에 던졌다.
        // 그 동작을 유지한다 — 전부 시도하고, 실패가 있었으면 그때 알린다.
        //
        // 취소가 위험한 이유가 하나 더 있다. @Async 퓨처는 supplyAsync 기반이라
        // cancel(false)로 워커를 끊지 못한다. 취소된 형제들의 풀 스레드는
        // per-call-timeout-ms(50초) 동안 버려질 결과를 계속 기다린다.
        val failures = coroutineScope {
            banks.map { bank ->
                async(Dispatchers.IO) {
                    val result = asyncBankCallWorker.call(runId, bank, request).await()
                    runCatching { onEachResult(result) }
                }
            }.awaitAll()
        }.mapNotNull { it.exceptionOrNull() }

        failures.firstOrNull()?.let { first ->
            log.error("Result persistence failed for ${failures.size}/${banks.size} banks", first)
            throw first
        }

        log.info("Async-threadpool fan-out finished bankCount=${banks.size}")
    }

    companion object {
        private val log = LoggerFactory.getLogger(AsyncThreadPoolBankFanOutExecutor::class.java)
    }
}
