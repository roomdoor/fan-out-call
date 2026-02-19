package com.example.loanlimit.bankcallresult.service

import com.example.loanlimit.config.AppProperties
import org.springframework.stereotype.Service

@Service
class BankCatalogService(
    private val appProperties: AppProperties,
) {
    fun getAll(): List<String> {
        return (1..appProperties.banks.count).map { number ->
            "BANK-${number.toString().padStart(2, '0')}"
        }
    }
}
