package com.parvez.android.notification;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;


class BookEventStreamTest {
    @Test
    void newConnectionsStartAtLatestAndReconnectsKeepTheirCursor() {
        assertEquals(42, BookEventStream.parseCursor(null, 42));
        assertEquals(0, BookEventStream.parseCursor("0", 42));
        assertEquals(12, BookEventStream.parseCursor("12", 42));
    }

    @Test
    void rejectsInvalidAndFutureCursors() {
        for (String cursor : new String[]{"", "abc", "-1", "43", "9223372036854775808"}) {
            var error = assertThrows(ResponseStatusException.class, () -> BookEventStream.parseCursor(cursor, 42));
            assertEquals(400, error.getStatusCode().value());
        }
    }

    @Test
    void limitsConcurrentConnections() {
        var store = new BookEventStore(null) {
            @Override public long latestId() { return 0; }
            @Override public java.util.List<Event> after(long cursor, java.util.UUID workspaceId) { return java.util.List.of(); }
        };
        var stream = new BookEventStream(store, 1, 1000);
        try {
            stream.subscribe(null, java.util.UUID.randomUUID());
            var error = assertThrows(ResponseStatusException.class, () -> stream.subscribe(null, java.util.UUID.randomUUID()));
            assertEquals(503, error.getStatusCode().value());
        } finally {
            stream.shutdown();
        }
    }

    @Test void oneHundredIdleReadersShareCursorPollingAndWakeForNewEvents() throws Exception {
        var globalCursor = new AtomicLong();
        var cursorQueries = new AtomicInteger();
        var workspaceQueries = new AtomicInteger();
        var workspaces = java.util.concurrent.ConcurrentHashMap.<java.util.UUID>newKeySet();
        var store = new BookEventStore(null) {
            @Override public long latestId() { cursorQueries.incrementAndGet(); return globalCursor.get(); }
            @Override public java.util.List<Event> after(long cursor, java.util.UUID workspaceId) {
                workspaces.add(workspaceId);
                workspaceQueries.incrementAndGet();
                return java.util.List.of();
            }
        };
        var stream = new BookEventStream(store, 100, 50);
        try {
            for (int i = 0; i < 100; i++) stream.subscribe(null, java.util.UUID.randomUUID());
            await(() -> workspaceQueries.get() == 100);
            int idleQueries = workspaceQueries.get();
            int latestQueries = cursorQueries.get();
            Thread.sleep(200);
            assertEquals(idleQueries, workspaceQueries.get(), "Idle connections must not query their event history repeatedly");
            assertTrue(cursorQueries.get() - latestQueries <= 8, "Global checks are shared across all connections");
            assertEquals(100, workspaces.size());
            globalCursor.set(1);
            await(() -> workspaceQueries.get() == 200);
        } finally { stream.shutdown(); }
    }

    @Test void fullReplayBatchesDrainWithoutWaitingForAnotherGlobalChange() throws Exception {
        var cursors = new java.util.concurrent.CopyOnWriteArrayList<Long>();
        var scope = java.util.UUID.randomUUID();
        var store = new BookEventStore(null) {
            @Override public long latestId() { return 150; }
            @Override public java.util.List<Event> after(long cursor, java.util.UUID workspaceId) {
                assertEquals(scope, workspaceId);
                cursors.add(cursor);
                return java.util.stream.LongStream.rangeClosed(cursor + 1, Math.min(150, cursor + 100))
                        .mapToObj(id -> new Event(id, "{}")).toList();
            }
        };
        var stream = new BookEventStream(store, 1, 50);
        try {
            stream.subscribe("0", scope);
            await(() -> cursors.size() >= 2);
            assertEquals(java.util.List.of(0L, 100L), cursors);
        } finally { stream.shutdown(); }
    }

    private void await(java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!done.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(done.getAsBoolean(), "Timed out waiting for event delivery");
    }
}
