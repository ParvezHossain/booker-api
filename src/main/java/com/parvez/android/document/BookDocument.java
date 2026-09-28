package com.parvez.android.document;

import java.time.Instant;
import java.util.UUID;

/** Internal storage details are deliberately absent from the public DTO. */
public record BookDocument(UUID id, long bookId, String storageProvider, String storageKey,
                           String fileName, long fileSize, int pageCount, String checksum,
                           String sourceType, boolean active, Instant createdAt) {
    public DocumentResponse response() {
        return new DocumentResponse(bookId, id, fileName, fileSize, "application/pdf", pageCount,
                checksum, sourceType, active, createdAt);
    }
    public record DocumentResponse(long bookId, UUID documentId, String fileName, long fileSize,
                                   String mimeType, int pageCount, String checksum, String sourceType,
                                   boolean active, Instant createdAt) {}
}
