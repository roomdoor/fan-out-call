package com.example.loanlimit.config

import com.example.loanlimit.logging.CorrelationHeaders
import com.example.loanlimit.logging.MdcKeys
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class CorrelationContextFilterTest {

    private val filter = CorrelationContextFilter()

    @Test
    fun `submit without headers returns bad request`() {
        val request = MockHttpServletRequest("POST", "/api/v1/loan-limit/coroutine/queries")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, NoOpChain())

        assertEquals(400, response.status)
    }

    @Test
    fun `submit with headers sets mdc in chain and restores after`() {
        MDC.put(MdcKeys.RUN_ID, "prev-run")
        val request = MockHttpServletRequest("POST", "/api/v1/loan-limit/coroutine/queries")
        request.addHeader(CorrelationHeaders.X_BORROWER_ID, "B-1")
        val response = MockHttpServletResponse()

        var chainCalled = false
        filter.doFilter(request, response, FilterChain { _: ServletRequest, _: ServletResponse ->
            chainCalled = true
            assertEquals("B-1", MDC.get(MdcKeys.BORROWER_ID))
        })

        assertTrue(chainCalled)
        assertEquals("prev-run", MDC.get(MdcKeys.RUN_ID))
        assertNull(MDC.get(MdcKeys.BORROWER_ID))
        MDC.clear()
    }

    @Test
    fun `poll by transactionNo sets transactionNo mdc`() {
        val request = MockHttpServletRequest("GET", "/api/v1/loan-limit/queries/number/42")
        request.addHeader(CorrelationHeaders.X_BORROWER_ID, "B-2")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _: ServletRequest, _: ServletResponse ->
            assertEquals("B-2", MDC.get(MdcKeys.BORROWER_ID))
            assertEquals("42", MDC.get(MdcKeys.RUN_ID))
        })

        assertNull(MDC.get(MdcKeys.RUN_ID))
    }

    @Test
    fun `actuator health bypasses header enforcement`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        val response = MockHttpServletResponse()

        var chainCalled = false
        filter.doFilter(request, response, FilterChain { _: ServletRequest, _: ServletResponse ->
            chainCalled = true
        })

        assertTrue(chainCalled)
        assertEquals(200, response.status)
    }

    private class NoOpChain : FilterChain {
        override fun doFilter(request: ServletRequest, response: ServletResponse) = Unit
    }
}
