package com.parvez.android.library;

import com.parvez.android.exception.GlobalExceptionHandler;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PublicLibraryRequestControllerTest {
    private final PublicLibraryRequestService service = mock(PublicLibraryRequestService.class);
    private final UUID requestId = UUID.randomUUID();
    private ValidatorFactory validators;
    private MockMvc mvc;

    @BeforeEach void setup() {
        validators = Validation.buildDefaultValidatorFactory();
        mvc = MockMvcBuilders.standaloneSetup(new PublicLibraryRequestController(
                service, JsonMapper.builder().build(), validators.getValidator()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach void cleanup() { validators.close(); }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"text/plain", "application/octet-stream", "application/json"})
    void acceptsJsonMetadataWithCurlAndTypedPartContentTypes(String contentType) throws Exception {
        mvc.perform(multipart("/api/admin/public-book-requests/{id}/accept", requestId)
                .file(metadata(contentType, "{\"publishedDate\":\"2026\",\"description\":\"Review\",\"completed\":false}"))
                .file(pdf()))
                .andExpect(status().isOk());
        verify(service).accept(eq(requestId), eq(new PublicLibraryRequestService.Metadata("2026", "Review", false)),
                eq("book.pdf"), eq("application/pdf"), any(InputStream.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-json", "null", "{}", "{\"publishedDate\":\" \"}",
            "{\"publishedDate\":\"123456789012345678901\"}", "{\"completed\":{}}"})
    void rejectsInvalidMetadataBeforeCallingService(String json) throws Exception {
        mvc.perform(multipart("/api/admin/public-book-requests/{id}/accept", requestId)
                .file(metadata(null, json)).file(pdf()))
                .andExpect(status().isBadRequest()).andExpect(content().contentType("application/json"));
        verifyNoInteractions(service);
    }

    @Test void rejectsOversizedDescription() throws Exception {
        mvc.perform(multipart("/api/admin/public-book-requests/{id}/accept", requestId)
                .file(metadata(null, "{\"publishedDate\":\"2026\",\"description\":\"" + "x".repeat(5001) + "\"}"))
                .file(pdf())).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test void rejectsMissingMetadata() throws Exception {
        mvc.perform(multipart("/api/admin/public-book-requests/{id}/accept", requestId).file(pdf()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    private MockMultipartFile metadata(String contentType, String json) {
        return new MockMultipartFile("metadata", "", contentType, json.getBytes(StandardCharsets.UTF_8));
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "book.pdf", "application/pdf", new byte[]{1});
    }
}
