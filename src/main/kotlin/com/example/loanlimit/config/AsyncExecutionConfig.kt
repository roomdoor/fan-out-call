package com.example.loanlimit.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.core.task.TaskDecorator
import com.example.loanlimit.logging.restoreMdc
import org.slf4j.MDC
import java.util.concurrent.Executor

@Configuration
@EnableAsync
class AsyncExecutionConfig(
    private val appProperties: AppProperties,
) {
    @Bean(name = [BANK_ASYNC_EXECUTOR])
    fun bankAsyncExecutor(): Executor {
        return ThreadPoolTaskExecutor().apply {
            corePoolSize = appProperties.asyncThreadPool.corePoolSize
            maxPoolSize = appProperties.asyncThreadPool.maxPoolSize
            queueCapacity = appProperties.asyncThreadPool.queueCapacity
            setThreadNamePrefix(appProperties.asyncThreadPool.threadNamePrefix)
            setTaskDecorator(MdcCopyingTaskDecorator())
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(30)
            initialize()
        }
    }

    private class MdcCopyingTaskDecorator : TaskDecorator {
        override fun decorate(runnable: Runnable): Runnable {
            val contextMap = MDC.getCopyOfContextMap()
            return Runnable {
                val previous = MDC.getCopyOfContextMap()
                try {
                    restoreMdc(contextMap)
                    runnable.run()
                } finally {
                    restoreMdc(previous)
                }
            }
        }
    }

    companion object {
        const val BANK_ASYNC_EXECUTOR = "bankAsyncExecutor"
    }
}
