package com.parvez.android.document;

import com.parvez.android.saas.WorkspacePrincipal;
import com.parvez.android.storage.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BookDocumentUploadFailureTest {
    FileStorageService storage;
    BookDocumentRepository repository;
    PdfInspector inspector;
    BookDocumentService service;
    final String key = UUID.randomUUID() + ".pdf";

    @BeforeEach void setup() {
        storage = mock(FileStorageService.class);
        repository = mock(BookDocumentRepository.class);
        inspector = mock(PdfInspector.class);
        when(repository.operation(anyLong(), anyString(), any())).thenReturn(Optional.empty());
        service = new BookDocumentService(mock(BookAccess.class), repository, storage, inspector,
                mock(TransactionTemplate.class), mock(JdbcTemplate.class), DataSize.ofMegabytes(2), DataSize.ofGigabytes(5));
        var principal = new WorkspacePrincipal("reader@example.com", "unused", UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void storageWriteFailureReturnsSafeServiceUnavailable() throws Exception {
        when(storage.upload(any(), anyLong())).thenThrow(new IOException("private filesystem path"));
        var failure = assertThrows(ResponseStatusException.class, this::upload);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
        assertEquals("Document storage is unavailable", failure.getReason());
        verify(repository, never()).activate(any(), anyString(), any());
    }

    @Test void storageReadFailureCleansUpAndReturnsServiceUnavailable() throws Exception {
        when(storage.upload(any(), anyLong())).thenReturn(new FileStorageService.StoredFile(key, 100, "checksum"));
        when(storage.download(key)).thenThrow(new IOException("private filesystem path"));
        var failure = assertThrows(ResponseStatusException.class, this::upload);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.getStatusCode());
        assertEquals("Document storage is unavailable", failure.getReason());
        verify(storage).delete(key);
        verify(repository, never()).activate(any(), anyString(), any());
    }

    @Test void failedCleanupDoesNotHidePdfValidationError() throws Exception {
        when(storage.upload(any(), anyLong())).thenReturn(new FileStorageService.StoredFile(key, 100, "checksum"));
        var resource = new org.springframework.core.io.ByteArrayResource(new byte[100]);
        when(storage.download(key)).thenReturn(resource);
        var rejected = new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Invalid PDF");
        when(inspector.inspect(resource)).thenThrow(rejected);
        doThrow(new IOException("Storage unavailable during cleanup")).when(storage).delete(key);
        assertSame(rejected, assertThrows(ResponseStatusException.class, this::upload));
        verify(storage).delete(key);
        verify(repository, never()).activate(any(), anyString(), any());
    }

    private BookDocument upload() throws IOException {
        return service.upload(1, UUID.randomUUID(), "book.pdf", "application/pdf",
                new ByteArrayInputStream(new byte[100]), "UPLOAD");
    }
}
