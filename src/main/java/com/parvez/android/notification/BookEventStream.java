package com.parvez.android.notification;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class BookEventStream {
    private static final Logger log = LoggerFactory.getLogger(BookEventStream.class);
    private final BookEventStore store;
    private final Semaphore slots;
    private final long pollMillis;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<SseEmitter> connections = ConcurrentHashMap.newKeySet();

    public BookEventStream(BookEventStore store,
                           @Value("${books.notifications.max-connections:200}") int maxConnections,
                           @Value("${books.notifications.poll-millis:1000}") long pollMillis) {
        if (maxConnections < 1 || pollMillis < 1) {
            throw new IllegalArgumentException("Notification limits must be positive");
        }
        this.store = store;
        this.slots = new Semaphore(maxConnections);
        this.pollMillis = pollMillis;
    }

    public SseEmitter subscribe(String lastEventId, java.util.UUID workspaceId) {
        long latest = store.latestId();
        long cursor = parseCursor(lastEventId, latest);
        if (!slots.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Notification connection limit reached");
        }
        SseEmitter emitter = new SseEmitter(300_000L);
        AtomicBoolean closed = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) {
                connections.remove(emitter);
                slots.release();
            }
        };
        connections.add(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> {
            cleanup.run();
            emitter.complete();
        });
        emitter.onError(error -> cleanup.run());
        try {
            workers.submit(() -> deliver(emitter, cursor, closed, cleanup, workspaceId));
        } catch (RuntimeException error) {
            cleanup.run();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Notification service is stopping");
        }
        return emitter;
    }

    static long parseCursor(String value, long latest) {
        if (value == null) return latest;
        try {
            long cursor = Long.parseLong(value);
            if (cursor < 0 || cursor > latest) throw new NumberFormatException();
            return cursor;
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Last-Event-ID must be between 0 and " + latest);
        }
    }

    private void deliver(SseEmitter emitter, long cursor, AtomicBoolean closed, Runnable cleanup, java.util.UUID workspaceId) {
        try {
            // Supplies a resume cursor even when no book has been created yet.
            emitter.send(SseEmitter.event().name("ready").id(Long.toString(cursor))
                    .reconnectTime(3000).data("{}", MediaType.APPLICATION_JSON));
            long heartbeatAt = System.nanoTime();
            while (!closed.get() && !Thread.currentThread().isInterrupted()) {
                var events = store.after(cursor, workspaceId);
                for (var event : events) {
                    if (closed.get()) return;
                    emitter.send(SseEmitter.event().name(event.type()).id(Long.toString(event.id()))
                            .data(event.payload(), MediaType.APPLICATION_JSON));
                    cursor = event.id();
                }
                if (System.nanoTime() - heartbeatAt >= 15_000_000_000L) {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                    heartbeatAt = System.nanoTime();
                }
                if (events.size() < 100) Thread.sleep(pollMillis);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException error) {
            // The servlet container completes disconnected requests.
            log.debug("Book event connection closed", error);
        } catch (RuntimeException error) {
            log.warn("Book event delivery failed; client can reconnect using its cursor", error);
        } finally {
            cleanup.run();
            emitter.complete();
        }
    }

    @PreDestroy
    void shutdown() {
        workers.shutdownNow();
        connections.forEach(SseEmitter::complete);
    }
}
