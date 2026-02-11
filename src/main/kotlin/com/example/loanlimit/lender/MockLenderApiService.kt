package com.example.loanlimit.lender

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.domain.LenderApiProfile
import com.example.loanlimit.domain.MockExternalCallResult
import com.example.loanlimit.dto.LoanLimitQueryRequest
import com.example.loanlimit.entity.LenderCallResultEntity
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import kotlin.math.roundToLong
import kotlin.random.Random

class MockLenderApiService(
    override val lenderCode: String,
    private val appProperties: AppProperties,
) : LenderApiService {
    override fun buildRequest(request: LoanLimitQueryRequest): String {
        return """{"customer":{"id":"${request.borrowerId}"},"income":{"annual":${request.annualIncome}},"loan":{"requestedAmount":${request.requestedAmount}}}"""
    }

    override suspend fun callApi(
        profile: LenderApiProfile,
        request: LoanLimitQueryRequest,
        requestPayload: String,
    ): MockExternalCallResult {
        delay(resolveLatencyMs(profile.lenderCode))

        val success = Random.nextInt(1, 101) <= appProperties.mock.successRatePercent

        return nestedJsonResult(request, requestPayload, success)
    }

    private fun resolveLatencyMs(lenderCode: String): Long {
        val (rangeStart, rangeEnd) = if (isSlowLender(lenderCode)) {
            appProperties.mock.slowMinLatencyMs to appProperties.mock.slowMaxLatencyMs
        } else {
            appProperties.mock.minLatencyMs to appProperties.mock.maxLatencyMs
        }

        val lower = minOf(rangeStart, rangeEnd)
        val upper = maxOf(rangeStart, rangeEnd)
        return Random.nextLong(lower, upper + 1)
    }

    private fun isSlowLender(lenderCode: String): Boolean {
        val lenderNumber = lenderCode.substringAfter("LENDER-").toIntOrNull() ?: return false
        val slowCount = appProperties.mock.slowLenderCount.coerceIn(0, appProperties.lenders.count)
        if (slowCount == 0) {
            return false
        }

        val slowStartNumber = appProperties.lenders.count - slowCount + 1
        return lenderNumber >= slowStartNumber
    }

    override fun toEntity(
        runId: Long,
        profile: LenderApiProfile,
        requestPayload: String,
        response: MockExternalCallResult,
        requestedAt: LocalDateTime,
        respondedAt: LocalDateTime,
        latencyMs: Long,
    ): LenderCallResultEntity {
        val success = response.httpStatus in 200..299 && response.approvedLimit != null

        return LenderCallResultEntity(
            runId = runId,
            lenderCode = profile.lenderCode,
            host = profile.host,
            url = profile.url,
            httpStatus = response.httpStatus,
            success = success,
            responseCode = response.responseCode,
            responseMessage = response.responseMessage,
            approvedLimit = response.approvedLimit,
            latencyMs = latencyMs,
            errorDetail = if (success) null else response.responseMessage,
            requestPayload = requestPayload,
            responsePayload = response.responsePayload,
            requestedAt = requestedAt,
            respondedAt = respondedAt,
        )
    }

    private fun nestedJsonResult(
        request: LoanLimitQueryRequest,
        requestPayload: String,
        success: Boolean,
    ): MockExternalCallResult {
        val baseLimit = (request.annualIncome * 1.1).roundToLong()
        val approvedLimit = if (success) minOf(request.requestedAmount, baseLimit) else null

        val responsePayload = if (success) {
            """{"status":{"code":"S000","message":"SUCCESS"},"data":{"limit":$approvedLimit}}"""
        } else {
            """{"status":{"code":"E503","message":"Provider timeout"}}"""
        }

        return MockExternalCallResult(
            httpStatus = if (success) 200 else 503,
            responseCode = if (success) "S000" else "E503",
            responseMessage = if (success) "Approved" else "Upstream timeout",
            approvedLimit = approvedLimit,
            requestPayload = requestPayload,
            responsePayload = responsePayload,
        )
    }
}
