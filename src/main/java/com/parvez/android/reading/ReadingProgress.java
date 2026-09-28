package com.parvez.android.reading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/** Personal completion is based on the furthest page of this document, never Book.completed. */
public record ReadingProgress(long bookId, UUID documentId, int currentPage, int totalPages, int pagesRead,
                              BigDecimal progressPercentage, Instant lastReadAt,
                              @io.swagger.v3.oas.annotations.media.Schema(description = "True when this account has reached the final page of this document. Backward navigation preserves completion; document replacement resets it. Independent of Book.completed and rounded percentage.")
                              boolean completed,
                              int resumePage, long version) {
    public static ReadingProgress of(long bookId, UUID documentId, int current, int total, int maximum, Instant lastRead, long version) {
        // Compare page counts, not the rounded percentage, which can reach 100 before the final page.
        return new ReadingProgress(bookId, documentId, current, total, maximum,
                BigDecimal.valueOf(maximum).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP),
                lastRead, maximum == total, Math.max(1, current), version);
    }
}
