package com.example.loanlimit.lender

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.service.LenderCatalogService
import org.springframework.stereotype.Component

@Component
class LenderApiServiceRegistry(
    lenderCatalogService: LenderCatalogService,
    appProperties: AppProperties,
) {
    private val servicesByLenderCode: Map<String, LenderApiService> = lenderCatalogService.getAll().associate { profile ->
        profile.lenderCode to MockLenderApiService(
            lenderCode = profile.lenderCode,
            appProperties = appProperties,
        )
    }

    fun get(lenderCode: String): LenderApiService {
        return servicesByLenderCode[lenderCode]
            ?: error("No lender service registered for lenderCode: $lenderCode")
    }
}
