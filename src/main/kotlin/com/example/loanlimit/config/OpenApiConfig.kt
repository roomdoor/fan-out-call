package com.example.loanlimit.config

import com.example.loanlimit.logging.CorrelationHeaders
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.parameters.Parameter
import org.springdoc.core.customizers.OperationCustomizer
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

    @Bean
    fun correlationHeaderOperationCustomizer(): OperationCustomizer {
        return OperationCustomizer { operation, _ ->
            val parameters = (operation.parameters ?: emptyList()).toMutableList()
            if (parameters.none { it.`in` == "header" && it.name == CorrelationHeaders.X_BORROWER_ID }) {
                parameters.add(
                    Parameter()
                        .`in`("header")
                        .name(CorrelationHeaders.X_BORROWER_ID)
                        .required(true)
                        .description("Borrower identifier for correlation"),
                )
            }
            operation.parameters = parameters
            operation
        }
    }
}
