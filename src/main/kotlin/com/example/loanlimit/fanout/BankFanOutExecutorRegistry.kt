package com.example.loanlimit.fanout

import com.example.loanlimit.fanout.asyncpool.AsyncThreadPoolBankFanOutExecutor
import com.example.loanlimit.fanout.coroutine.CoroutineBankFanOutExecutor
import com.example.loanlimit.fanout.sequential.SequentialSingleThreadBankFanOutExecutor
import com.example.loanlimit.fanout.webclient.WebClientBankFanOutExecutor
import org.springframework.stereotype.Component

@Component
class BankFanOutExecutorRegistry(
    coroutineBankFanOutExecutor: CoroutineBankFanOutExecutor,
    sequentialSingleThreadBankFanOutExecutor: SequentialSingleThreadBankFanOutExecutor,
    asyncThreadPoolBankFanOutExecutor: AsyncThreadPoolBankFanOutExecutor,
    webClientBankFanOutExecutor: WebClientBankFanOutExecutor,
) {
    private val executorsByModeName: Map<String, BankFanOutExecutor> = mapOf(
        MODE_COROUTINE to coroutineBankFanOutExecutor,
        MODE_SEQUENTIAL_SINGLE_THREAD to sequentialSingleThreadBankFanOutExecutor,
        MODE_ASYNC_THREADPOOL to asyncThreadPoolBankFanOutExecutor,
        MODE_WEBCLIENT_NON_BLOCKING to webClientBankFanOutExecutor,
    )

    fun get(modeName: String): BankFanOutExecutor {
        return executorsByModeName[modeName]
            ?: error("No fan-out executor registered for modeName=$modeName")
    }

    companion object {
        const val MODE_COROUTINE = "COROUTINE"
        const val MODE_SEQUENTIAL_SINGLE_THREAD = "SEQUENTIAL_SINGLE_THREAD"
        const val MODE_ASYNC_THREADPOOL = "ASYNC_THREADPOOL"
        const val MODE_WEBCLIENT_NON_BLOCKING = "WEBCLIENT_NON_BLOCKING"
    }
}
