package com.example.loanlimit.config

import com.example.loanlimit.logging.MdcKeys
import io.micrometer.context.ContextRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.MDC
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Hooks

@Configuration
class ReactorContextPropagationConfig {
    @PostConstruct
    fun init() {
        registerMdcAccessor(MdcKeys.RUN_ID)
        registerMdcAccessor(MdcKeys.BORROWER_ID)
        registerMdcAccessor(MdcKeys.BANK_CODE)
        Hooks.enableAutomaticContextPropagation()
    }

    private fun registerMdcAccessor(key: String) {
        ContextRegistry.getInstance().registerThreadLocalAccessor(
            key,
            { MDC.get(key) },
            { value ->
                if (value == null) {
                    MDC.remove(key)
                } else {
                    MDC.put(key, value)
                }
            },
            { MDC.remove(key) },
        )
    }
}
