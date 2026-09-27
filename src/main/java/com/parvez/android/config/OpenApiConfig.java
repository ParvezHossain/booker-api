package com.parvez.android.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Booker SaaS API")
                        .version("1.0.0")
                        .description("Multi-tenant book catalogue API. First create a workspace with POST /api/auth/signup, then use Authorize with your registered email and password (HTTP Basic). There is no login or token endpoint. All book operations and notification replay are scoped to your authenticated workspace; no workspace header is required. FREE workspaces allow 100 books. PRO entitlements are operator-managed; billing and self-service upgrades are not implemented. Use HTTPS outside local development."))
                .components(new Components()
                        .addSecuritySchemes("basicAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("Registered owner email and password. Shared admin/admin credentials are not supported.")));
    }
}