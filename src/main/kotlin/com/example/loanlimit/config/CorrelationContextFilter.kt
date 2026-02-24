package com.example.loanlimit.config

import com.example.loanlimit.logging.CorrelationHeaders
import com.example.loanlimit.logging.MdcKeys
import com.example.loanlimit.logging.restoreMdc
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class CorrelationContextFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val path = request.requestUriWithoutContextPath()
        if (!isProtectedPath(request.method, path) || isExcludedPath(path)) {
            filterChain.doFilter(request, response)
            return
        }

        val requiredHeaders = resolveRequiredHeaders(request, response) ?: return
        val borrowerId = requiredHeaders.borrowerId

        val prevMdc = MDC.getCopyOfContextMap()
        try {
            MDC.put(MdcKeys.BORROWER_ID, borrowerId)

            transactionNoFrom(path)?.let { MDC.put(MdcKeys.RUN_ID, it) }

            filterChain.doFilter(request, response)
        } finally {
            restoreMdc(prevMdc)
        }
    }

    private fun isProtectedPath(method: String, path: String): Boolean {
        return when (method.uppercase()) {
            "POST" -> SUBMIT_PATH.matches(path)
            "GET" -> POLL_BY_TRANSACTION_NO_PATH.matches(path) || POLL_BY_REQUEST_ID_PATH.matches(path)
            else -> false
        }
    }

    private fun resolveRequiredHeaders(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): RequiredHeaders? {
        val borrowerId = request.getHeader(CorrelationHeaders.X_BORROWER_ID)
        if (borrowerId.isNullOrBlank()) {
            response.status = HttpServletResponse.SC_BAD_REQUEST
            response.contentType = "text/plain"
            response.writer.write("missing required headers")
            return null
        }
        return RequiredHeaders(borrowerId)
    }

    private fun isExcludedPath(path: String): Boolean {
        return path == "/swagger-ui.html" ||
            path == "/v3/api-docs" ||
            path.startsWith("/actuator/") ||
            path.startsWith("/swagger-ui/")
    }

    private fun transactionNoFrom(path: String): String? {
        return POLL_BY_TRANSACTION_NO_PATH.matchEntire(path)?.groupValues?.get(1)
    }

    private fun HttpServletRequest.requestUriWithoutContextPath(): String {
        val contextPath = contextPath.orEmpty()
        val uri = requestURI.orEmpty()
        return if (contextPath.isNotEmpty() && uri.startsWith(contextPath)) {
            uri.substring(contextPath.length)
        } else {
            uri
        }
    }

    companion object {
        private val SUBMIT_PATH = Regex("^/api/v1/loan-limit/[^/]+/queries$")
        private val POLL_BY_REQUEST_ID_PATH = Regex("^/api/v1/loan-limit/queries/request/([^/]+)$")
        private val POLL_BY_TRANSACTION_NO_PATH = Regex("^/api/v1/loan-limit/queries/number/([^/]+)$")
    }

    private data class RequiredHeaders(
        val borrowerId: String,
    )
}
