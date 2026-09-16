package com.example.loanlimit.fanout.asyncpool

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.fanout.BankFanOutExecutor
import com.example.loanlimit.loanlimitbatchrun.dto.request.LoanLimitQueryRequest
import com.example.loanlimit.bankcallresult.entity.BankCallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.util.concurrent.RejectedExecutionException

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
        // 제출 실패 격리는 모드마다 구조가 달라 각 executor가 맡는다.
        coroutineScope {
            banks.map { bank ->
                async(Dispatchers.IO) {
                    // 풀이 거부하면(queue 가득 + maxPool 도달) @Async 프록시가
                    // 퓨처를 만들기 전에 동기적으로 던진다. 그대로 두면 형제 은행이
                    // 전부 취소되고 이미 받아온 결과까지 버려진다. 은행 하나가
                    // 거부된 것이므로 그 은행의 실패로 기록한다.
                    //
                    // 동기적으로 오는 것은 RejectedExecutionException 뿐이다.
                    // 워커 본문(registry.get, buildRequest)의 실패는 @Async라
                    // 퓨처가 예외적으로 완료되는 형태로 온다.
                    val result = try {
                        asyncBankCallWorker.call(runId, bank, request).await()
                    } catch (e: CancellationException) {
                        // 취소는 실패가 아니다. 삼키면 진행 중인 호출이
                        // REJECTED 행으로 기록되고 취소가 전파되지 않는다.
                        throw e
                    } catch (e: RejectedExecutionException) {
                        log.warn("Bank call submission rejected bankCode=$bank message=${e.message}")
                        failureResult(runId, bank, "REJECTED", "Executor rejected bank call", e)
                    } catch (e: Exception) {
                        // 거부가 아닌 제출 단계 실패(알 수 없는 은행 코드, 요청 직렬화 등).
                        // 독립성은 유지하되 REJECTED와 섞지 않는다 — 섞으면
                        // 거부 카운트가 설정 오류까지 세게 된다.
                        //
                        // EXCEPTION은 AsyncBankCallWorker가 평범한 타임아웃·HTTP
                        // 오류에 이미 쓰는 코드라 DB에서 구분이 안 된다. 별도 코드를 쓴다.
                        log.warn("Bank call submission failed bankCode=$bank errorType=${e::class.simpleName} message=${e.message}")
                        failureResult(runId, bank, "SUBMIT_ERROR", "Bank call submission failed", e)
                    }
                    onEachResult(result)
                }
            }.awaitAll()
        }

        log.info("Async-threadpool fan-out finished bankCount=${banks.size}")
    }

    // 제출 단계에서 끝난 은행. 호출을 보내기 전이라 payload는 비어 있다.
    private fun failureResult(
        runId: Long,
        bankCode: String,
        responseCode: String,
        responseMessage: String,
        e: Exception,
    ): BankCallResult {
        val now = LocalDateTime.now()
        // 이 함수가 던지면 격리가 깨져 형제 코루틴이 취소된다. 호스트 해석은
        // 은행 코드 형식을 검증하므로 실패할 수 있어 안전하게 처리한다.
        val host = runCatching { appProperties.webClientFanOut.resolveMockBaseUrl(bankCode) }
            .getOrDefault("unresolved")
        return BankCallResult(
            runId = runId,
            bankCode = bankCode,
            host = host,
            url = "/api/v1/mock-external/banks/$bankCode/loan-limit",
            httpStatus = null,
            success = false,
            responseCode = responseCode,
            responseMessage = responseMessage,
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
