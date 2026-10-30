package com.example.statemachine.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI workflowApiDocumentation() {
        return new OpenAPI()
                .info(
                     new Info()
                    .title("State Machine Brain API")
                    .version("1.0")
                    .description("Database-configured onboarding workflow. One business endpoint; JDBC persistence and idempotent mutations.")
                );
    }
}
