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
import java.time.LocalDateTime

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
        //
        // 저장 실패 격리는 LoanLimitQueryOrchestrator가 onEachResult를 감싸서
        // 처리한다. 네 모드가 같은 정책을 쓰도록 한 곳에 뒀다.
        coroutineScope {
            banks.map { bank ->
                async(Dispatchers.IO) {
                    // 풀이 거부하면(queue 가득 + maxPool 도달) @Async 프록시가
                    // 퓨처를 만들기 전에 동기적으로 던진다. 그대로 두면 형제 은행이
                    // 전부 취소되고 이미 받아온 결과까지 버려진다. 은행 하나가
                    // 거부된 것이므로 그 은행의 실패로 기록한다.
                    val result = try {
                        asyncBankCallWorker.call(runId, bank, request).await()
                    } catch (e: Exception) {
                        log.warn("Bank call submission rejected bankCode=$bank errorType=${e::class.simpleName} message=${e.message}")
                        rejectedResult(runId, bank, e)
                    }
                    onEachResult(result)
                }
            }.awaitAll()
        }

        log.info("Async-threadpool fan-out finished bankCount=${banks.size}")
    }

    // 제출 자체가 거부된 은행. 요청을 만들기 전이라 payload는 비어 있다.
    // responseCode를 EXCEPTION과 구분해 두면 로그에서 풀 거부를 따로 셀 수 있다.
    private fun rejectedResult(
        runId: Long,
        bankCode: String,
        e: Exception,
    ): BankCallResult {
        val now = LocalDateTime.now()
        return BankCallResult(
            runId = runId,
            bankCode = bankCode,
            host = appProperties.webClientFanOut.resolveMockBaseUrl(bankCode),
            url = "/api/v1/mock-external/banks/$bankCode/loan-limit",
            httpStatus = null,
            success = false,
            responseCode = "REJECTED",
            responseMessage = "Executor rejected bank call",
            approvedLimit = null,
            latencyMs = 0,
            errorDetail = e.message,
            requestPayload = "{}",
            responsePayload = "{}",
            requestedAt = now,
            respondedAt = now,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(AsyncThreadPoolBankFanOutExecutor::class.java)
    }
}
