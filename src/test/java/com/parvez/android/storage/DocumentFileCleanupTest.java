package com.parvez.android.storage;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.IOException;
import java.util.List;
import static org.mockito.Mockito.*;

class DocumentFileCleanupTest {
    @Test void storageFailureRetainsReceiptAndLaterSuccessfulRetryAcknowledgesIt() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var storage = mock(FileStorageService.class);
        when(storage.provider()).thenReturn("LOCAL");
        String key = "00000000-0000-4000-8000-000000000000.pdf";
        String query = "SELECT storage_key FROM document_file_deletions WHERE storage_provider = ? ORDER BY created_at LIMIT 100";
        when(jdbc.queryForList(query, String.class, "LOCAL")).thenReturn(List.of(key));
        doThrow(new IOException("storage unavailable")).doNothing().when(storage).delete(key);
        var cleanup = new DocumentFileCleanup(jdbc, storage);
        cleanup.processPending();
        verify(jdbc, never()).update("DELETE FROM document_file_deletions WHERE storage_key = ? AND storage_provider = ?", key, "LOCAL");
        cleanup.processPending();
        verify(jdbc).update("DELETE FROM document_file_deletions WHERE storage_key = ? AND storage_provider = ?", key, "LOCAL");
    }
}
