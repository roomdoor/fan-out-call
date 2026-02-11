package com.example.loanlimit

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class LoanLimitGatewayApplication

fun main(args: Array<String>) {
    runApplication<LoanLimitGatewayApplication>(*args)
}
