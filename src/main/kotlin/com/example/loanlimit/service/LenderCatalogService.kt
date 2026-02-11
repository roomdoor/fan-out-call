package com.example.loanlimit.service

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.domain.LenderApiProfile
import org.springframework.stereotype.Service

@Service
class LenderCatalogService(
    private val appProperties: AppProperties,
) {
    fun getAll(): List<LenderApiProfile> {
        return (1..appProperties.lenders.count).map { number ->
            LenderApiProfile(
                lenderCode = "LENDER-${number.toString().padStart(2, '0')}",
                host = "api.lender-${number.toString().padStart(2, '0')}.mock.finance.local",
                url = "/v${(number % 5) + 1}/loan-limit/check/${number.toString().padStart(2, '0')}",
            )
        }
    }
}
