package com.parvez.android.document;

import com.parvez.android.saas.WorkspacePrincipal;
import com.parvez.android.storage.FileStorageService;
import com.parvez.android.storage.StorageUploadException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class BookDocumentService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BookDocumentService.class);
    private final BookAccess access;
    private final BookDocumentRepository documents;
    private final FileStorageService storage;
    private final PdfInspector inspector;
    private final TransactionTemplate transaction;
    private final JdbcTemplate jdbc;
    private final long maxBytes;
    private final long workspaceBytes;
    public BookDocumentService(BookAccess access, BookDocumentRepository documents, FileStorageService storage,
                               PdfInspector inspector, TransactionTemplate transaction, JdbcTemplate jdbc,
                               @Value("${books.documents.max-size:200MB}") DataSize maxSize,
                               @Value("${books.documents.workspace-limit:5GB}") DataSize workspaceLimit) {
        this.access = access; this.documents = documents; this.storage = storage; this.inspector = inspector;
        this.transaction = transaction; this.jdbc = jdbc;
        maxBytes = maxSize.toBytes(); workspaceBytes = workspaceLimit.toBytes();
        if (maxBytes < 1 || workspaceBytes < 1) throw new IllegalArgumentException("Document size limits must be positive");
    }
    public BookDocument active(long bookId) {
        access.require(bookId);
        return activeDocument(bookId);
    }
    public BookDocument activePublic(long bookId) {
        access.requirePublic(bookId);
        return activeDocument(bookId);
    }
    private BookDocument activeDocument(long bookId) {
        return documents.active(bookId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Book has no PDF"));
    }
    public BookDocument uploadPublic(long bookId, UUID operation, String name, InputStream input) throws IOException {
        WorkspacePrincipal.requireSuperAdmin();
        return upload(bookId, operation, name, "application/pdf", input, "UPLOAD", true);
    }
    public Resource content(BookDocument doc) {
        if (!storage.provider().equals(doc.storageProvider())) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document storage is unavailable");
        try { return storage.download(doc.storageKey()); }
        catch (IOException ex) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document content is unavailable"); }
    }
    public BookDocument upload(long bookId, UUID operation, String name, String mimeType,
                               InputStream input, String source) throws IOException {
        return upload(bookId, operation, name, mimeType, input, source, false);
    }
    private BookDocument upload(long bookId, UUID operation, String name, String mimeType,
                                InputStream input, String source, boolean publicLibrary) throws IOException {
        if (publicLibrary) access.requirePublic(bookId); else access.require(bookId);
        String email = WorkspacePrincipal.currentEmail();
        var replay = documents.operation(bookId, email, operation);
        if (replay.isPresent()) return replay.get();
        String filename = sanitize(name);
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".pdf") || !"application/pdf".equalsIgnoreCase(mimeType))
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "A .pdf file with application/pdf content type is required");
        FileStorageService.StoredFile stored;
        try { stored = storage.upload(input, publicLibrary ? Long.MAX_VALUE : maxBytes); }
        catch (StorageUploadException ex) {
            throw new ResponseStatusException(ex.reason() == StorageUploadException.Reason.TOO_LARGE
                    ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.BAD_REQUEST,
                    ex.reason() == StorageUploadException.Reason.TOO_LARGE ? "PDF exceeds the upload limit" : "PDF is empty");
        }
        catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document storage is unavailable");
        }
        // Validate outside the database transaction; keep locks short while parsing large files.
        boolean retained = false;
        try {
            int pages = publicLibrary ? inspector.inspectPublic(storage.download(stored.key()))
                    : inspector.inspect(storage.download(stored.key()));
            BookDocument result = transaction.execute(status -> {
                if (publicLibrary) { WorkspacePrincipal.requireSuperAdmin(); access.lockPublic(bookId); }
                else access.lock(bookId);
                var previous = documents.operation(bookId, email, operation);
                if (previous.isPresent()) return previous.get();
                if (!publicLibrary) {
                    // Shared workspace budget includes retained versions and serializes uploads to different books.
                    jdbc.queryForObject("SELECT id FROM workspaces WHERE id = ? FOR UPDATE", UUID.class, WorkspacePrincipal.currentWorkspace());
                    long used = jdbc.queryForObject("SELECT coalesce(sum(d.file_size), 0) FROM book_documents d JOIN books b ON b.id = d.book_id WHERE b.workspace_id = ?",
                            Long.class, WorkspacePrincipal.currentWorkspace());
                    if (stored.size() > workspaceBytes - used)
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Workspace document storage limit reached");
                }
                var doc = new BookDocument(UUID.randomUUID(), bookId, storage.provider(), stored.key(), filename,
                        stored.size(), pages, stored.checksum(), source, true, Instant.now());
                documents.activate(doc, email, operation);
                return documents.active(bookId).orElseThrow();
            });
            retained = result != null && result.storageKey().equals(stored.key());
            if (retained && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCompletion(int status) {
                                if (status != STATUS_COMMITTED) {
                                    try { storage.delete(stored.key()); }
                                    catch (IOException ex) { log.warn("Could not remove rolled back document upload"); }
                                }
                            }
                        });
            }
            return result;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Document storage is unavailable");
        } finally {
            if (!retained) {
                try { storage.delete(stored.key()); }
                catch (IOException ex) {
                    // Preserve the original validation/transaction outcome; reconcile orphan files later.
                    log.warn("Could not remove unreferenced upload {} from {}", stored.key(), storage.provider(), ex);
                }
            }
        }
    }
    private String sanitize(String name) {
        if (name == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filename is required");
        String cleaned = name.replace('\\', '/');
        cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_").strip();
        if (cleaned.isEmpty() || cleaned.length() > 255) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Filename must contain 1–255 characters");
        return cleaned;
    }
}
