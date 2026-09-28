package com.parvez.android.drive;

import com.parvez.android.document.*;
import com.parvez.android.saas.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Service
public class GoogleDriveImportService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final BookAccess access;
    private final BookDocumentService documents;
    private final WorkspaceAccounts accounts;
    private final GoogleDriveConnectionService connections;
    private final GoogleDriveGateway gateway;
    private final GoogleDriveSettings settings;
    private final long maxBytes;
    public GoogleDriveImportService(JdbcTemplate jdbc, TransactionTemplate transaction, BookAccess access,
                                    BookDocumentService documents, WorkspaceAccounts accounts,
                                    GoogleDriveConnectionService connections, GoogleDriveGateway gateway,
                                    GoogleDriveSettings settings, @Value("${books.documents.max-size:200MB}") DataSize maxSize) {
        this.jdbc = jdbc; this.transaction = transaction; this.access = access; this.documents = documents;
        this.accounts = accounts; this.connections = connections; this.gateway = gateway; this.settings = settings;
        maxBytes = maxSize.toBytes();
    }
    public ImportStatus start(long bookId, UUID operation, String fileId) {
        access.require(bookId);
        // Completed/pending operations remain replayable after disconnect; new work needs a grant.
        if (jdbc.queryForObject("SELECT count(*) FROM google_drive_imports WHERE id = ? AND book_id = ? AND user_email = ?",
                Integer.class, operation, bookId, WorkspacePrincipal.currentEmail()) > 0) {
            var existing = status(bookId, operation);
            if (!fileId.equals(existing.fileId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation ID already used for another file");
            return existing;
        }
        if (!connections.connected()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Connect Google Drive first");
        jdbc.update("INSERT INTO google_drive_imports (id, book_id, user_email, file_id) VALUES (?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
                operation, bookId, WorkspacePrincipal.currentEmail(), fileId);
        var result = status(bookId, operation);
        if (!fileId.equals(result.fileId())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Operation ID already used for another file");
        return result;
    }
    public ImportStatus status(long bookId, UUID id) {
        access.require(bookId);
        return jdbc.query("SELECT * FROM google_drive_imports WHERE id = ? AND book_id = ? AND user_email = ?",
                (rs, row) -> new ImportStatus(id, bookId, rs.getString("file_id"), rs.getString("status"), rs.getObject("document_id", UUID.class), rs.getString("message")),
                id, bookId, WorkspacePrincipal.currentEmail()).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Import not found"));
    }
    @Scheduled(fixedDelayString = "${books.google-drive.poll-millis:2000}", initialDelayString = "${books.google-drive.initial-delay:2000}")
    public void processNext() {
        if (!settings.enabled()) return;
        var jobs = transaction.execute(status -> jdbc.queryForList("""
                UPDATE google_drive_imports SET status = 'RUNNING', attempts = attempts + 1, updated_at = now(), available_at = now() + interval '15 minutes'
                WHERE id = (SELECT id FROM google_drive_imports WHERE (status = 'PENDING' OR status = 'RUNNING') AND available_at <= now()
                    ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1)
                RETURNING *
                """));
        if (jobs == null || jobs.isEmpty()) return;
        var job = jobs.getFirst(); UUID id = (UUID) job.get("id");
        int attempt = ((Number) job.get("attempts")).intValue();
        try {
            var user = accounts.loadUserByUsername((String) job.get("user_email"));
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
            long bookId = ((Number) job.get("book_id")).longValue();
            access.require(bookId);
            String token = connections.accessToken(), fileId = (String) job.get("file_id");
            var file = gateway.metadata(token, fileId);
            if (!file.canDownload()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Selected Drive file cannot be downloaded");
            if (!"application/pdf".equals(file.mimeType())) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Select a PDF from Google Drive");
            if (file.size() < 1 || file.size() > maxBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Selected Drive PDF exceeds the upload limit or is empty");
            var document = gateway.download(token, fileId, input -> documents.upload(bookId, id, file.name(), file.mimeType(), input, "GOOGLE_DRIVE"));
            jdbc.update("UPDATE google_drive_imports SET status = 'COMPLETED', document_id = ?, message = NULL, updated_at = now() WHERE id = ?", document.id(), id);
        } catch (Exception ex) {
            boolean retry = ex instanceof ResponseStatusException failure && failure.getStatusCode().value() == 503 && attempt < 3;
            String message = ex instanceof ResponseStatusException failure ? failure.getReason() : "Import failed; reconnect Google Drive and try again";
            long delaySeconds = attempt == 1 ? 30 : 60;
            jdbc.update("UPDATE google_drive_imports SET status = ?, message = ?, available_at = now() + (? * interval '1 second'), updated_at = now() WHERE id = ?",
                    retry ? "PENDING" : "FAILED", message, delaySeconds, id);
        } finally { SecurityContextHolder.clearContext(); }
    }
    public record ImportStatus(UUID importId, long bookId, String fileId, String status, UUID documentId, String message) {}
}
