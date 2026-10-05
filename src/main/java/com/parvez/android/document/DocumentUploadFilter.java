package com.parvez.android.document;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.io.IOException;
import java.util.concurrent.Semaphore;
import java.util.List;

/** Runs after security authorization, before MVC parses multipart or reads raw PDF bodies. */
public final class DocumentUploadFilter extends OncePerRequestFilter {
    private static final List<PathPattern> UPLOADS = List.of(
            PathPatternParser.defaultInstance.parse("/api/books/{bookId}/document"),
            PathPatternParser.defaultInstance.parse("/api/public-books/{bookId}/document"),
            PathPatternParser.defaultInstance.parse("/api/admin/public-book-requests/{requestId}/accept"));
    private final Semaphore uploads;
    private final int retrySeconds;
    private final HandlerExceptionResolver errors;

    public DocumentUploadFilter(int maximumUploads, int retrySeconds, HandlerExceptionResolver errors) {
        if (maximumUploads < 1 || maximumUploads > 32 || retrySeconds < 1 || retrySeconds > 300)
            throw new IllegalArgumentException("Upload concurrency must be 1–32 and retry delay 1–300 seconds");
        uploads = new Semaphore(maximumUploads);
        this.retrySeconds = retrySeconds;
        this.errors = errors;
    }

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) return true;
        var path = PathContainer.parsePath(request.getRequestURI().substring(request.getContextPath().length()));
        // Match decoded segments just like MVC, so percent-encoded route names cannot bypass admission.
        return UPLOADS.stream().noneMatch(pattern -> pattern.matches(path));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        if (!uploads.tryAcquire()) {
            var busy = new PdfCapacityException("PDF upload capacity is busy; retry later", retrySeconds);
            errors.resolveException(request, response, null, busy);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            uploads.release();
        }
    }
}
