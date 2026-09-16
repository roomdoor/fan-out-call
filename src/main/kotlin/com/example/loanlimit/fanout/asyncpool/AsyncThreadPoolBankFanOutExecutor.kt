package com.example.loanlimit.fanout.asyncpool

import com.example.loanlimit.bank.BankApiServiceRegistry
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
    private val bankApiServiceRegistry: BankApiServiceRegistry,
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

        // join()이 아니라 await()다. join()은 IO 워커를 run이 끝날 때까지
        // 붙잡는데 결과 저장도 같은 디스패처를 쓰므로, 동시 run이 워커 수를
        // 넘으면 교착한다(기본 64 워커 / 31초 = 약 124 RPM).
        coroutineScope {
            banks.map { bank ->
                async(Dispatchers.IO) {
                    val result = try {
                        asyncBankCallWorker.call(runId, bank, request).await()
                    } catch (e: CancellationException) {
                        // 아래 catch들이 취소를 삼키지 않게 막는다. 지우면
                        // 진행 중이던 호출이 REJECTED 행으로 기록된다.
                        throw e
                    } catch (e: RejectedExecutionException) {
                        // 풀 거부는 @Async 프록시가 동기적으로 던진다.
                        // 그냥 두면 형제 은행이 전부 취소된다.
                        log.warn("Bank call submission rejected bankCode=$bank message=${e.message}")
                        submissionFailure(runId, bank, "REJECTED", "Executor rejected bank call", e)
                    } catch (e: Exception) {
                        // 설정 오류 등. REJECTED(부하 신호)와 EXCEPTION(호출 실패)
                        // 어느 쪽과도 섞이면 안 되므로 별도 코드를 쓴다.
                        log.warn("Bank call submission failed bankCode=$bank errorType=${e::class.simpleName} message=${e.message}")
                        submissionFailure(runId, bank, "SUBMIT_ERROR", "Bank call submission failed", e)
                    }
                    onEachResult(result)
                }
            }.awaitAll()
        }

        log.info("Async-threadpool fan-out finished bankCount=${banks.size}")
    }

    // 제출 단계에서 끝난 은행. 호출을 보내기 전이라 payload는 비어 있다.
    private fun submissionFailure(
        runId: Long,
        bankCode: String,
        responseCode: String,
        responseMessage: String,
        e: Exception,
    ): BankCallResult {
        val now = LocalDateTime.now()
        return bankApiServiceRegistry.toFailureEntity(
            runId = runId,
            bankCode = bankCode,
            requestPayload = "{}",
            responseCode = responseCode,
            responseMessage = responseMessage,
            errorDetail = e.message,
            latencyMs = 0,
            requestedAt = now,
            respondedAt = now,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(AsyncThreadPoolBankFanOutExecutor::class.java)
    }
}
