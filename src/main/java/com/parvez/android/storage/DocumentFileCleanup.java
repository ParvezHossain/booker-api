package com.parvez.android.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Committed deletion receipts survive crashes/storage failures; deletion is idempotent across replicas. */
@Component
public class DocumentFileCleanup {
    private static final Logger log = LoggerFactory.getLogger(DocumentFileCleanup.class);
    private final JdbcTemplate jdbc;
    private final FileStorageService storage;
    public DocumentFileCleanup(JdbcTemplate jdbc, FileStorageService storage) { this.jdbc = jdbc; this.storage = storage; }
    @Scheduled(fixedDelayString = "${books.storage.cleanup-poll-millis:30000}", initialDelayString = "${books.storage.cleanup-poll-millis:30000}")
    public void processPending() {
        var keys = jdbc.queryForList("SELECT storage_key FROM document_file_deletions WHERE storage_provider = ? ORDER BY created_at LIMIT 100",
                String.class, storage.provider());
        for (String key : keys) {
            try {
                storage.delete(key);
                jdbc.update("DELETE FROM document_file_deletions WHERE storage_key = ? AND storage_provider = ?", key, storage.provider());
            } catch (Exception ex) { log.warn("Public document deletion will be retried", ex); }
        }
    }
}
