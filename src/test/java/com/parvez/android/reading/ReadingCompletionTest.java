package com.parvez.android.reading;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ReadingCompletionTest {
    @Test void unopenedSinglePageDocumentIsNotCompleted() {
        var progress = ReadingProgress.of(1, UUID.randomUUID(), 0, 1, 0, null, 0);
        assertFalse(progress.completed());
        assertEquals(1, progress.resumePage());
        assertEquals("0.00", progress.progressPercentage().toPlainString());
    }

    @Test void reachingOnlyPageCompletesSinglePageDocument() {
        var progress = ReadingProgress.of(1, UUID.randomUUID(), 1, 1, 1, Instant.now(), 1);
        assertTrue(progress.completed());
        assertEquals("100.00", progress.progressPercentage().toPlainString());
    }

    @Test void returningToEarlierPagePreservesCompletion() {
        var progress = ReadingProgress.of(1, UUID.randomUUID(), 20, 144, 144, Instant.now(), 2);
        assertTrue(progress.completed());
        assertEquals(20, progress.resumePage());
        assertEquals(144, progress.pagesRead());
    }

    @Test void roundedHundredPercentDoesNotCompleteUnreadFinalPage() {
        var progress = ReadingProgress.of(1, UUID.randomUUID(), 19999, 20000, 19999, Instant.now(), 1);
        assertEquals("100.00", progress.progressPercentage().toPlainString());
        assertFalse(progress.completed());
    }
}
