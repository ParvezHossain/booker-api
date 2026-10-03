package com.parvez.android.config;

import io.swagger.v3.oas.annotations.Operation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

@SpringBootTest
class OpenApiCoverageTest {
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;
    JsonNode specification;

    @BeforeEach void loadSpecification() throws Exception {
        MockMvc mvc = webAppContextSetup(context).apply(springSecurity()).build();
        String json = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        specification = JsonMapper.builder().build().readTree(json);
    }

    @Test void everyApplicationMappingHasExplicitDocumentation() {
        int operations = 0;
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            var handler = entry.getValue();
            if (!handler.getBeanType().getPackageName().startsWith("com.parvez.android")) continue;
            assertNotNull(handler.getMethodAnnotation(Operation.class), handler.toString());
            var mapping = entry.getKey();
            assertNotNull(mapping.getPathPatternsCondition());
            for (String path : mapping.getPathPatternsCondition().getPatternValues()) {
                for (var method : mapping.getMethodsCondition().getMethods()) {
                    var operation = specification.path("paths").path(path).path(method.name().toLowerCase(Locale.ROOT));
                    assertFalse(operation.isMissingNode(), method + " " + path);
                    assertFalse(operation.path("summary").asText().isBlank(), method + " " + path);
                    assertFalse(operation.path("tags").isEmpty(), method + " " + path);
                    assertFalse(operation.path("responses").isEmpty(), method + " " + path);
                    if (path.startsWith("/api/")) {
                        boolean publicRoute = List.of("/api/auth/signup", "/api/auth/login", "/api/auth/refresh", "/api/auth/logout",
                                "/api/auth/forgot-password", "/api/auth/reset-password", "/api/integrations/google-drive/callback").contains(path);
                        assertEquals(publicRoute, operation.path("security").isEmpty(), "Authentication docs: " + method + " " + path);
                        assertTrue(List.of("200", "201", "202", "204", "206").stream()
                                .anyMatch(operation.path("responses")::has), "Success response missing: " + method + " " + path);
                        operations++;
                    }
                }
            }
        }
        assertEquals(43, operations, "Update the client inventory when adding an API");
    }

    @Test void markdownInventoryMatchesApplicationRoutesAndHealth() throws Exception {
        String markdown = Files.readString(Path.of("API.md"));
        String inventory = markdown.substring(markdown.indexOf("## 5. Endpoint inventory"),
                markdown.indexOf("## 6. Authentication and passwords"));
        var pattern = java.util.regex.Pattern.compile("(?m)^\\| (GET|HEAD|POST|PUT|DELETE|PATCH) \\| `([^`]+)` \\|");
        var matcher = pattern.matcher(inventory);
        var documented = new java.util.TreeSet<String>();
        while (matcher.find()) {
            String route = matcher.group(1) + " " + matcher.group(2);
            assertTrue(documented.add(route), "Duplicate documented route: " + route);
        }
        var implemented = new java.util.TreeSet<String>();
        for (var entry : mappings.getHandlerMethods().entrySet()) {
            if (!entry.getValue().getBeanType().getPackageName().startsWith("com.parvez.android")) continue;
            for (String path : entry.getKey().getPathPatternsCondition().getPatternValues()) {
                for (var method : entry.getKey().getMethodsCondition().getMethods()) {
                    implemented.add(method.name() + " " + path);
                }
            }
        }
        implemented.add("GET /actuator/health");
        assertEquals(implemented, documented, "Keep API.md synchronized with controller mappings");
    }

    @Test void authenticationAndRecoveryDocumentSecurityAndSecrets() {
        var health = specification.path("paths").path("/actuator/health").path("get");
        assertFalse(health.path("summary").asText().isBlank());
        assertTrue(health.path("security").isEmpty());
        assertTrue(health.path("responses").has("503"));
        for (String path : List.of("signup", "login", "refresh", "logout", "forgot-password", "reset-password")) {
            assertTrue(specification.path("paths").path("/api/auth/" + path).path("post").path("security").isEmpty(), path);
        }
        var change = specification.path("paths").path("/api/auth/change-password").path("post");
        assertFalse(change.path("security").isEmpty());
        assertTrue(change.path("responses").has("204"));
        var schemas = specification.path("components").path("schemas");
        assertTrue(schemas.path("LoginRequest").path("properties").path("password").path("writeOnly").asBoolean());
        assertTrue(schemas.path("ChangePasswordRequest").path("properties").path("newPassword").path("writeOnly").asBoolean());
        assertTrue(schemas.path("ResetPasswordRequest").path("properties").path("token").path("writeOnly").asBoolean());
    }

    @Test void pdfRoutesDescribeBinaryContentRangesAndBodylessHead() {
        for (String prefix : List.of("/api/books", "/api/public-books")) {
            var path = specification.path("paths").path(prefix + "/{bookId}/document/content");
            var content = path.path("get");
            for (String code : List.of("200", "206")) {
                var schema = content.path("responses").path(code).path("content").path("application/pdf").path("schema");
                assertEquals("binary", schema.path("format").asText(), prefix + " " + code);
            }
            boolean range = false;
            for (var parameter : content.path("parameters")) {
                if ("Range".equals(parameter.path("name").asText()) && "header".equals(parameter.path("in").asText())) range = true;
            }
            assertTrue(range, prefix);
            assertTrue(path.path("head").path("responses").path("200").path("content").isEmpty());
            assertFalse(content.path("security").isEmpty());
        }
    }
    @Test void validationAndUploadErrorsUseApiErrorRatherThanSuccessDtos() {
        for (String path : List.of("/api/books/{bookId}/document", "/api/public-books/{bookId}/document")) {
            for (String code : List.of("400", "415", "503")) {
                var error = specification.path("paths").path(path).path("post").path("responses").path(code)
                        .path("content").path("application/json").path("schema");
                assertEquals("#/components/schemas/ApiError", error.path("$ref").asText(), path + " " + code);
            }
        }
        var conflict = specification.path("paths").path("/api/books/{bookId}/reading-progress").path("put")
                .path("responses").path("409").path("content").path("application/json").path("schema").path("oneOf");
        assertEquals(2, conflict.size(), "Both revision and replaced-document error shapes must remain documented");
    }

}
