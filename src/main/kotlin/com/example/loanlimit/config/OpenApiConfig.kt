package com.example.loanlimit.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun loanLimitOpenApi(): OpenAPI {
        return OpenAPI().info(
            Info()
                .title("Loan Limit Gateway API")
                .description(
                    "Asynchronous bank fan-out API for loan-limit inquiry. " +
                        "POST creates a transaction and returns immediately; polling endpoints return progress and results."
                )
                .version("v1"),
        )
    }
}
