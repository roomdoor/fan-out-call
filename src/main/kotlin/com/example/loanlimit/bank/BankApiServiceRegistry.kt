package com.example.loanlimit.bank

import com.example.loanlimit.config.AppProperties
import com.example.loanlimit.bankcallresult.service.BankCatalogService
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.stereotype.Component

@Component
class BankApiServiceRegistry(
    bankCatalogService: BankCatalogService,
    appProperties: AppProperties,
    webClientBuilder: WebClient.Builder,
) {
    private val servicesByBankCode: Map<String, BankApiService> = bankCatalogService.getAll().associate { bankCode ->
        bankCode to ExternalBankApiService(
            bankCode = bankCode,
            appProperties = appProperties,
            webClientBuilder = webClientBuilder,
        )
    }

    fun get(bankCode: String): BankApiService {
        return servicesByBankCode[bankCode]
            ?: error("No bank service registered for bankCode: $bankCode")
    }
}
