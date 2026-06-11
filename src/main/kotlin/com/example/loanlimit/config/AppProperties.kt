package com.example.loanlimit.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val banks: Banks = Banks(),
    val asyncThreadPool: AsyncThreadPool = AsyncThreadPool(),
    val webClientFanOut: WebClientFanOut = WebClientFanOut(),
) {
    data class Banks(
        val count: Int = 50,
        val parallelism: Int = 50,
        val perCallTimeoutMs: Long = 5_000,
        val requiredCompletionMs: Long = 60_000,
    )

    data class AsyncThreadPool(
        val corePoolSize: Int = 50,
        val maxPoolSize: Int = 64,
        val queueCapacity: Int = 100,
        val threadNamePrefix: String = "loan-limit-async-",
        // true면 platform thread pool 대신 virtual thread per-task executor 사용 (pool/queue 설정 무시)
        val virtual: Boolean = false,
    )

    data class WebClientFanOut(
        val mockBaseUrl: String = "http://localhost:8080",
        val routingMode: MockRoutingMode = MockRoutingMode.SINGLE,
        val shardedMockRouting: ShardedMockRouting = ShardedMockRouting(),
        val maxConcurrency: Int = 50,
        // 공유 WebClient 커넥션 풀 (모든 모드 공통)
        val maxConnections: Int = 2_000,
        val pendingAcquireMaxCount: Int = 10_000,
    ) {
        fun resolveMockBaseUrl(bankCode: String): String {
            return when (routingMode) {
                MockRoutingMode.SINGLE -> mockBaseUrl
                MockRoutingMode.SHARDED -> resolveShardedMockBaseUrl(bankCode)
            }
        }

        private fun resolveShardedMockBaseUrl(bankCode: String): String {
            require(shardedMockRouting.shardCount > 0) {
                "app.web-client-fan-out.sharded-mock-routing.shard-count must be greater than 0"
            }

            val bankNumber = parseBankNumber(bankCode)
            val shardOffset = (bankNumber - 1) % shardedMockRouting.shardCount
            val mockUri = URI(mockBaseUrl)
            val scheme = requireNotNull(mockUri.scheme) {
                "app.web-client-fan-out.mock-base-url must include a valid scheme"
            }
            val host = requireNotNull(mockUri.host) {
                "app.web-client-fan-out.mock-base-url must include a valid host"
            }

            return "$scheme://$host:${shardedMockRouting.basePort + shardOffset}"
        }

        private fun parseBankNumber(bankCode: String): Int {
            val match = BANK_CODE_REGEX.matchEntire(bankCode)
            require(match != null) { "Invalid bank code format: $bankCode (expected BANK-XX)" }
            return match.groupValues[1].toInt()
        }

        companion object {
            private val BANK_CODE_REGEX = Regex("^BANK-(\\d{2})$")
        }
    }

    enum class MockRoutingMode {
        SINGLE,
        SHARDED,
    }

    data class ShardedMockRouting(
        val basePort: Int = 18_000,
        val shardCount: Int = 10,
    )
}
