package com.parvez.android.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.List;
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
                        .version("2.1.0")
                        .description("Multi-tenant book catalogue API. First create a workspace with POST /api/auth/signup, then POST /api/auth/login with your email and password. Use Authorize with the returned accessToken (bearerAuth). POST /api/auth/refresh rotates the refresh token; POST /api/auth/logout revokes it. Password change and emailed reset endpoints revoke all existing account tokens. HTTP Basic remains supported for compatibility. Books use numeric IDs and the exact author/title pair is unique per workspace. Private book operations and notification replay are scoped to your authenticated workspace. Public Library routes require authentication; management requires Super Admin and public reading progress is workspace-shared; no workspace header is required. FREE workspaces allow 100 books. PRO entitlements are operator-managed; billing and self-service upgrades are not implemented. Use HTTPS outside local development."))
                .path("/actuator/health", healthPath())
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"))
                        .addSecuritySchemes("basicAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("basic")
                                .description("Registered owner or Super Admin email and password. Shared admin/admin credentials are not supported.")));
    }

    /** Actuator owns this public route, so its contract cannot be annotated in an application controller. */
    private PathItem healthPath() {
        var schema = new ObjectSchema().addProperty("status", new StringSchema().example("UP"));
        var content = new Content().addMediaType("application/json", new MediaType().schema(schema));
        return new PathItem().get(new Operation()
                .summary("Check service health")
                .description("Public operational endpoint. Returns the aggregate health status without component details. "
                        + "200 means the health endpoint completed with an available status; 503 indicates an unhealthy service.")
                .tags(List.of("Operations"))
                .security(List.of())
                .responses(new ApiResponses()
                        .addApiResponse("200", new ApiResponse().description("Service health status").content(content))
                        .addApiResponse("503", new ApiResponse().description("Service is unhealthy or out of service").content(content))));
    }

}