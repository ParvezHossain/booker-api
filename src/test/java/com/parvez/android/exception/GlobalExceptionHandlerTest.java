package com.parvez.android.exception;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class GlobalExceptionHandlerTest {
    @RestController
    static class ProbeController {
        @GetMapping(value = "/probe", produces = MediaType.APPLICATION_JSON_VALUE)
        public Map<String, String> get() { return Map.of("status", "ok"); }

        @GetMapping("/constraint")
        public void constraint() {
            // PostgreSQL exceptions can include failing-row values, including protected credentials.
            throw new DataIntegrityViolationException("duplicate uk_books_workspace_author_title; secret-row-value");
        }
    }

    @Test void unsupportedMethodReturns405AndAllowHeader() throws Exception {
        var mvc = standaloneSetup(new ProbeController()).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/probe"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "GET"))
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.path").value("/probe"));
    }

    @Test void unacceptableRepresentationReturns406WithoutClaimingServerFailure() throws Exception {
        var mvc = standaloneSetup(new ProbeController()).setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/probe").accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(jsonPath("$.status").value(406));
    }

    @Test void constraintErrorsKeepSafeDuplicateMessageWithoutLoggingRowValues() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        var events = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var mvc = standaloneSetup(new ProbeController()).setControllerAdvice(new GlobalExceptionHandler()).build();
            mvc.perform(get("/constraint"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("A book with this author and title already exists in your workspace"));
            assertFalse(events.list.isEmpty());
            for (var event : events.list) {
                assertFalse(event.getFormattedMessage().contains("secret-row-value"));
                assertNull(event.getThrowableProxy(), "Constraint exception stacks can contain database row values");
            }
        } finally {
            logger.detachAppender(events);
            events.stop();
        }
    }
}
