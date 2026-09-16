package com.example.loanlimit.bank

import com.example.loanlimit.bankcallresult.service.BankCatalogService
import com.example.loanlimit.config.AppProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDateTime

/**
 * 실패 행의 host가 샤드 주소로 채워지는지, 그리고 주소를 만들 수 없는
 * 은행이 와도 던지지 않는지 고정한다.
 *
 * 이전에는 executor마다 실패 행을 따로 만들어서 coroutine/sequential은
 * mockBaseUrl(샤딩 무시), async-threadpool은 resolveMockBaseUrl(샤드 반영)을
 * 썼다. 같은 은행이 모드에 따라 다른 host로 기록됐다.
 */
class FailureEntityHostTest {

    private val properties = AppProperties(
        banks = AppProperties.Banks(count = 20),
        webClientFanOut = AppProperties.WebClientFanOut(
            mockBaseUrl = "http://mock-host:18000",
            routingMode = AppProperties.MockRoutingMode.SHARDED,
            shardedMockRouting = AppProperties.ShardedMockRouting(basePort = 18000, shardCount = 10),
        ),
    )

    private val registry = BankApiServiceRegistry(
        bankCatalogService = BankCatalogService(properties),
        appProperties = properties,
        webClient = WebClient.builder().build(),
    )

    @Test
    fun `실패 행의 host가 그 은행의 샤드 주소로 채워진다`() {
        // BANK-03 은 (3-1) % 10 = 2 번 샤드 → 18002
        val result = failureFor("BANK-03")

        assertEquals("http://mock-host:18002", result.host)
        assertEquals("/api/v1/mock-external/banks/BANK-03/loan-limit", result.url)
    }

    @Test
    fun `샤드가 다른 은행은 host도 다르다`() {
        // 모드별로 따로 만들던 시절 mockBaseUrl 을 쓰던 쪽은 둘 다
        // 18000 으로 기록했다. 그래서 같은 run 안에서 host가 어긋났다.
        assertEquals("http://mock-host:18000", failureFor("BANK-01").host)
        assertEquals("http://mock-host:18009", failureFor("BANK-10").host)
    }

    @Test
    fun `주소를 만들 수 없는 은행이 와도 던지지 않는다`() {
        // 카탈로그에 없는 코드. 실패 행을 만드는 자리에서 던지면 격리가
        // 깨져 호출부의 catch 밖으로 나가고 형제 은행이 취소된다.
        val result = failureFor("BANK-999")

        assertEquals("unregistered", result.host)
        assertEquals(false, result.success)
    }

    @Test
    fun `등록된 은행이라도 주소 해석이 실패하면 unresolved로 남는다`() {
        // count=200 이면 카탈로그가 BANK-100 을 만드는데, 샤드 주소 파싱은
        // BANK-\d{2} 만 받으므로 resolveMockBaseUrl 이 던진다.
        val wide = properties.copy(banks = AppProperties.Banks(count = 200))
        val wideRegistry = BankApiServiceRegistry(
            bankCatalogService = BankCatalogService(wide),
            appProperties = wide,
            webClient = WebClient.builder().build(),
        )

        val now = LocalDateTime.now()
        val result = wideRegistry.toFailureEntity(
            runId = 1L,
            bankCode = "BANK-100",
            requestPayload = "{}",
            responseCode = "EXCEPTION",
            responseMessage = "External call failed",
            errorDetail = null,
            latencyMs = 0,
            requestedAt = now,
            respondedAt = now,
        )

        assertEquals("unresolved", result.host)
    }

    private fun failureFor(bankCode: String) = registry.toFailureEntity(
        runId = 1L,
        bankCode = bankCode,
        requestPayload = "{}",
        responseCode = "EXCEPTION",
        responseMessage = "External call failed",
        errorDetail = "boom",
        latencyMs = 12,
        requestedAt = LocalDateTime.now(),
        respondedAt = LocalDateTime.now(),
    )
}
