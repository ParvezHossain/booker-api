package com.parvez.android.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LocalFileStorageServiceTest {
    @TempDir Path directory;
    LocalFileStorageService storage;

    @BeforeEach void setup() throws IOException {
        storage = new LocalFileStorageService(directory.resolve("books").toString());
    }

    @Test void streamsBytesWithChecksumAndRepeatableDownloads() throws Exception {
        byte[] bytes = new byte[200000];
        new java.util.Random(1).nextBytes(bytes);
        var input = new ByteArrayInputStream(bytes) {
            @Override public void close() { fail("Storage must not close caller-owned input"); }
            @Override public synchronized int read(byte[] buffer, int offset, int length) {
                assertTrue(length <= 65536, "Upload must use bounded reads");
                return super.read(buffer, offset, length);
            }
        };
        var stored = storage.upload(input, bytes.length);
        assertEquals("LOCAL", storage.provider());
        assertEquals(bytes.length, stored.size());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), stored.checksum());
        assertTrue(stored.key().matches("[0-9a-f-]{36}\\.pdf"));
        assertTrue(storage.exists(stored.key()));
        var resource = storage.download(stored.key());
        assertEquals(bytes.length, resource.contentLength());
        for (int i = 0; i < 2; i++) {
            try (var downloaded = resource.getInputStream()) { assertArrayEquals(bytes, downloaded.readAllBytes()); }
        }
        var second = storage.upload(new ByteArrayInputStream(bytes), bytes.length);
        assertNotEquals(stored.key(), second.key());
        storage.delete(stored.key());
        storage.delete(stored.key());
        assertFalse(storage.exists(stored.key()));
        assertThrows(IOException.class, () -> storage.download(stored.key()));
        assertTrue(storage.exists(second.key()));
    }

    @Test void emptyAndOversizedUploadsLeaveNoFiles() throws Exception {
        var empty = assertThrows(StorageUploadException.class,
                () -> storage.upload(new ByteArrayInputStream(new byte[0]), 10));
        assertEquals(StorageUploadException.Reason.EMPTY, empty.reason());
        var oversized = assertThrows(StorageUploadException.class,
                () -> storage.upload(new ByteArrayInputStream(new byte[100000]), 70000));
        assertEquals(StorageUploadException.Reason.TOO_LARGE, oversized.reason());
        assertEmptyStorage();
    }

    @Test void failedInputRemovesPartialUpload() throws Exception {
        var input = new InputStream() {
            int reads;
            @Override public int read() throws IOException { throw new IOException("Input interrupted"); }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                if (reads++ == 0) { buffer[offset] = 1; return 1; }
                throw new IOException("Input interrupted");
            }
        };
        assertThrows(IOException.class, () -> storage.upload(input, 100));
        assertEmptyStorage();
    }

    @Test void invalidLimitsAreRejectedBeforeReading() throws Exception {
        var input = new InputStream() {
            @Override public int read() { fail("Invalid limit must be checked before reading"); return -1; }
        };
        assertThrows(IllegalArgumentException.class, () -> storage.upload(input, 0));
        assertThrows(IllegalArgumentException.class, () -> storage.upload(input, -1));
        assertEmptyStorage();
    }

    @Test void rejectsPathsAndMalformedStorageKeys() throws Exception {
        for (String key : new String[]{null, "", "../secret.pdf", "/tmp/secret.pdf", "..\\secret.pdf", "book.pdf", UUID.randomUUID() + ".pdf/../secret"}) {
            assertThrows(IllegalArgumentException.class, () -> storage.download(key));
            assertThrows(IllegalArgumentException.class, () -> storage.exists(key));
            assertThrows(IllegalArgumentException.class, () -> storage.delete(key));
        }
        assertEmptyStorage();
    }

    @Test void symbolicLinksCannotExposeExternalFiles() throws Exception {
        Path external = directory.resolve("external.pdf");
        Files.writeString(external, "private");
        String key = UUID.randomUUID() + ".pdf";
        Files.createSymbolicLink(directory.resolve("books").resolve(key), external);
        assertFalse(storage.exists(key));
        assertThrows(IOException.class, () -> storage.download(key));
        storage.delete(key);
        assertEquals("private", Files.readString(external));
        assertEmptyStorage();
    }

    private void assertEmptyStorage() throws IOException {
        try (var files = Files.list(directory.resolve("books"))) { assertEquals(0, files.count()); }
    }
}
