package com.parvez.android.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentUploadFilterTest {
    @ParameterizedTest
    @ValueSource(strings = {"/api/books/1/document", "/api/books/1/%64ocument", "/api/public-books/1/document",
            "/api/admin/public-book-requests/33333333-3333-4333-8333-333333333333/accept"})
    void overloadRejectsBeforeReadingBodyAndDoesNotBlockReaders(String path) throws Exception {
        var errors = mock(HandlerExceptionResolver.class);
        var filter = new DocumentUploadFilter(1, 7, errors);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var active = workers.submit(() -> {
                filter.doFilter(new MockHttpServletRequest("POST", path), new MockHttpServletResponse(), (request, response) -> {
                    entered.countDown();
                    try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                });
                return null;
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var excess = spy(new MockHttpServletRequest("POST", path));
                var downstream = mock(jakarta.servlet.FilterChain.class);
                filter.doFilter(excess, new MockHttpServletResponse(), downstream);
                verifyNoInteractions(downstream);
                verify(excess, never()).getInputStream();
                verify(excess, never()).getParts();
                verify(errors).resolveException(eq(excess), any(), isNull(), argThat(failure ->
                        failure instanceof ResponseStatusException status && status.getStatusCode().value() == 503
                                && "7".equals(status.getHeaders().getFirst("Retry-After"))));
                var read = new MockHttpServletRequest("GET", path + "/content");
                filter.doFilter(read, new MockHttpServletResponse(), downstream);
                verify(downstream).doFilter(eq(read), any());
            } finally { release.countDown(); }
            active.get(5, TimeUnit.SECONDS);
        }
        var next = mock(jakarta.servlet.FilterChain.class);
        filter.doFilter(new MockHttpServletRequest("POST", path), new MockHttpServletResponse(), next);
        verify(next).doFilter(any(), any());
    }

    @Test void failureReleasesCapacityAndContextPathIsSupported() throws Exception {
        var filter = new DocumentUploadFilter(1, 5, mock(HandlerExceptionResolver.class));
        var request = new MockHttpServletRequest("POST", "/booker/api/books/1/document");
        request.setContextPath("/booker");
        assertThrows(IOException.class, () -> filter.doFilter(request, new MockHttpServletResponse(),
                (req, res) -> { throw new IOException("Disconnected upload"); }));
        var next = mock(jakarta.servlet.FilterChain.class);
        filter.doFilter(new MockHttpServletRequest("POST", "/api/books/1/document"), new MockHttpServletResponse(), next);
        verify(next).doFilter(any(), any());
    }
}
