package com.example.loanlimit.config

import org.springframework.boot.context.properties.ConfigurationProperties

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
    )

    data class WebClientFanOut(
        val mockBaseUrl: String = "http://localhost:8080",
        val maxConcurrency: Int = 50,
    )
}
