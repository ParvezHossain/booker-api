package com.parvez.android.reading;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

public record ReadingProgress(long bookId, UUID documentId, int currentPage, int totalPages, int pagesRead,
                              BigDecimal progressPercentage, Instant lastReadAt, boolean completed,
                              int resumePage, long version) {
    public static ReadingProgress of(long bookId, UUID documentId, int current, int total, int maximum, Instant lastRead, long version) {
        return new ReadingProgress(bookId, documentId, current, total, maximum,
                BigDecimal.valueOf(maximum).multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP),
                lastRead, maximum == total, Math.max(1, current), version);
    }
}
