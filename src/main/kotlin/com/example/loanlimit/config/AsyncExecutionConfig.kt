package com.example.loanlimit.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.Executor

@Configuration
@EnableAsync
class AsyncExecutionConfig(
    private val appProperties: AppProperties,
) {
    @Bean(name = [LENDER_ASYNC_EXECUTOR])
    fun lenderAsyncExecutor(): Executor {
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = appProperties.asyncThreadPool.corePoolSize
            maxPoolSize = appProperties.asyncThreadPool.maxPoolSize
            queueCapacity = appProperties.asyncThreadPool.queueCapacity
            setThreadNamePrefix(appProperties.asyncThreadPool.threadNamePrefix)
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
            initialize()
        }
    }

    companion object {
        const val LENDER_ASYNC_EXECUTOR = "lenderAsyncExecutor"
    }
}
