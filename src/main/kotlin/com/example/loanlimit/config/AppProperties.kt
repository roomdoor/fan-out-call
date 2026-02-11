package com.example.loanlimit.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppProperties(
    val lenders: Lenders = Lenders(),
    val mock: Mock = Mock(),
) {
    data class Lenders(
        val count: Int = 50,
        val parallelism: Int = 50,
        val perCallTimeoutMs: Long = 5_000,
        val requiredCompletionMs: Long = 60_000,
    )

    data class Mock(
        val minLatencyMs: Long = 3_000,
        val maxLatencyMs: Long = 15_000,
        val slowMinLatencyMs: Long = 30_000,
        val slowMaxLatencyMs: Long = 45_000,
        val slowLenderCount: Int = 2,
        val successRatePercent: Int = 85,
    )
}
