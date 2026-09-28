package com.example.ledgers.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ledgersOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ledgers — Double-Entry Payments Ledger API")
                        .description("""
                                Multi-tenant, append-only double-entry ledger.

                                **Authentication:** `Authorization: Bearer <key>`
                                - `/admin/**` uses the root key (`LEDGER_ROOT_KEY`).
                                - `/v1/**` uses the tenant API key returned when the tenant is created.

                                **Amounts** are decimal strings (`"-450.00"`): debit positive, credit negative,
                                and every transaction must balance per asset.
                                """)
                        .version("M2"))
                .components(new Components()
                        .addSecuritySchemes("RootKey", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer")
                                .description("Root key from LEDGER_ROOT_KEY"))
                        .addSecuritySchemes("ApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer")
                                .description("Tenant API key (lk_live_...)")));
    }
}
