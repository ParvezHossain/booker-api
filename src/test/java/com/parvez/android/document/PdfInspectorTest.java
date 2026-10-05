package com.parvez.android.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class PdfInspectorTest {
    @Test void burstWaitsForParserAndContinuesAfterRelease() throws Exception {
        var inspector = new PdfInspector(10, 1, Duration.ofSeconds(5));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        byte[] pdf = pdf();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> inspector.inspect(blocked(pdf, entered, release)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var waiting = workers.submit(() -> inspector.inspectPublic(new ByteArrayResource(pdf)));
                assertThrows(java.util.concurrent.TimeoutException.class, () -> waiting.get(50, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(1, first.get(5, TimeUnit.SECONDS));
                assertEquals(1, waiting.get(5, TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
    }

    @Test void timeoutDoesNotReadPdfOrLeakPermitAndInvalidPdfReleasesPermit() throws Exception {
        var inspector = new PdfInspector(10, 1, Duration.ofMillis(20));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        byte[] pdf = pdf();
        var read = new AtomicBoolean();
        var untouched = new ByteArrayResource(pdf) {
            @Override public InputStream getInputStream() throws IOException { read.set(true); return super.getInputStream(); }
        };
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = workers.submit(() -> inspector.inspect(blocked(pdf, entered, release)));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var failure = assertThrows(ResponseStatusException.class, () -> inspector.inspect(untouched));
                assertEquals(503, failure.getStatusCode().value());
                assertEquals("5", failure.getHeaders().getFirst("Retry-After"));
                assertFalse(read.get());
            } finally { release.countDown(); }
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
        }
        assertEquals(415, assertThrows(ResponseStatusException.class,
                () -> inspector.inspect(new ByteArrayResource(new byte[5]))).getStatusCode().value());
        assertEquals(1, inspector.inspect(new ByteArrayResource(pdf)));
    }

    @Test void interruptedWaitKeepsInterruptAndDoesNotAcquirePermit() throws Exception {
        var inspector = new PdfInspector(10, 1, Duration.ofSeconds(5));
        byte[] pdf = pdf();
        try {
            Thread.currentThread().interrupt();
            assertEquals(503, assertThrows(ResponseStatusException.class,
                    () -> inspector.inspect(new ByteArrayResource(pdf))).getStatusCode().value());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals(1, inspector.inspect(new ByteArrayResource(pdf())));
    }

    @Test void unsafeConfigurationFailsAtStartup() {
        assertThrows(IllegalArgumentException.class, () -> new PdfInspector(0, 1, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PdfInspector(10, 0, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PdfInspector(10, 9, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new PdfInspector(10, 1, Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> new PdfInspector(10, 1, Duration.ofSeconds(31)));
    }

    private ByteArrayResource blocked(byte[] pdf, CountDownLatch entered, CountDownLatch release) {
        return new ByteArrayResource(pdf) {
            @Override public InputStream getInputStream() throws IOException {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test timed out"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                return super.getInputStream();
            }
        };
    }

    private byte[] pdf() throws IOException {
        try (var document = new PDDocument(); var bytes = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
