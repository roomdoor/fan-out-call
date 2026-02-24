package com.example.loanlimit.logging

import org.slf4j.MDC

fun restoreMdc(contextMap: Map<String, String>?) {
    if (contextMap == null) {
        MDC.clear()
    } else {
        MDC.setContextMap(contextMap)
    }
}

inline fun <T> withMdcValue(key: String, value: String?, block: () -> T): T {
    val previous = MDC.get(key)
    if (value == null) {
        MDC.remove(key)
    } else {
        MDC.put(key, value)
    }

    return try {
        block()
    } finally {
        if (previous == null) {
            MDC.remove(key)
        } else {
            MDC.put(key, previous)
        }
    }
}
