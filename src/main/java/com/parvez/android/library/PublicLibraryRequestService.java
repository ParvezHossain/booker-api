package com.parvez.android.library;

import com.parvez.android.document.BookDocumentService;
import com.parvez.android.dto.BookRequest;
import com.parvez.android.saas.WorkspacePrincipal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.io.InputStream;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class PublicLibraryRequestService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PublicLibraryRequestService.class);
    public record Submit(@NotBlank @Size(max=255) String title, @NotBlank @Size(max=255) String authorName) {}
    public record Metadata(@NotBlank @Size(max=20) String publishedDate, @Size(max=5000) String description, Boolean completed) {}
    private final JdbcTemplate jdbc;
    private final PublicBookService books;
    private final BookDocumentService documents;
    private final PublicRequestEmailTemplate emails;
    public PublicLibraryRequestService(JdbcTemplate jdbc, PublicBookService books, BookDocumentService documents,
                                       PublicRequestEmailTemplate emails) {
        this.jdbc = jdbc; this.books = books; this.documents = documents; this.emails = emails;
    }
    @Transactional
    public PublicLibraryBookRequest submit(Submit input) {
        UUID workspace = WorkspacePrincipal.currentWorkspace();
        String title = input.title().strip(), author = input.authorName().strip();
        if (jdbc.queryForObject("SELECT count(*) FROM books WHERE library_type='PUBLIC' AND title=? AND author=?", Long.class, title, author) > 0)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This book already exists in the public library");
        UUID id = UUID.randomUUID();
        int inserted = jdbc.update("INSERT INTO public_library_book_requests(id,title,author_name,workspace_id,requester_email) VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING",
                id, title, author, workspace, WorkspacePrincipal.currentEmail());
        if (inserted == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Your workspace already has a pending request for this book");
        var request = find(id, false);
        String workspaceName = jdbc.queryForObject("SELECT name FROM workspaces WHERE id=?", String.class, workspace);
        var email = emails.submission(request, workspaceName);
        int recipients = jdbc.update("""
                INSERT INTO public_request_emails(request_id, email_type, recipient, subject, message, html_message)
                SELECT ?, 'SUBMISSION', email, ?, ?, ? FROM workspace_users WHERE role='SUPER_ADMIN'
                """, id, email.subject(), email.text(), email.html());
        if (recipients == 0) log.warn("Public book request submitted without a provisioned Super Admin email recipient");
        return request;
    }
    public List<PublicLibraryBookRequest> own() {
        return jdbc.query("SELECT * FROM public_library_book_requests WHERE workspace_id=? ORDER BY created_at DESC", this::map, WorkspacePrincipal.currentWorkspace());
    }
    public List<PublicLibraryBookRequest> all(String status) {
        WorkspacePrincipal.requireSuperAdmin();
        if (status != null && !List.of("PENDING", "ACCEPTED", "REJECTED").contains(status))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid request status");
        return status == null ? jdbc.query("SELECT * FROM public_library_book_requests ORDER BY created_at DESC", this::map)
                : jdbc.query("SELECT * FROM public_library_book_requests WHERE status=? ORDER BY created_at DESC", this::map, status);
    }
    @Transactional(rollbackFor = Exception.class)
    public PublicLibraryBookRequest accept(UUID id, Metadata metadata, String filename, String mime, InputStream input) throws IOException {
        WorkspacePrincipal.requireSuperAdmin();
        var request = pending(id);
        if (!"application/pdf".equalsIgnoreCase(mime)) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "A PDF is required");
        var book = books.create(new BookRequest(request.title(), request.authorName(), metadata.publishedDate(), metadata.description(), Boolean.TRUE.equals(metadata.completed())));
        documents.uploadPublic(book.id(), UUID.randomUUID(), filename, input);
        return decide(request, "ACCEPTED", book.id());
    }
    @Transactional
    public PublicLibraryBookRequest reject(UUID id) {
        WorkspacePrincipal.requireSuperAdmin();
        return decide(pending(id), "REJECTED", null);
    }
    private PublicLibraryBookRequest pending(UUID id) {
        var request = find(id, true);
        if (!"PENDING".equals(request.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Request has already been processed");
        return request;
    }
    private PublicLibraryBookRequest decide(PublicLibraryBookRequest request, String status, Long bookId) {
        jdbc.update("UPDATE public_library_book_requests SET status=?,book_id=?,reviewed_by=?,reviewed_at=now() WHERE id=?",
                status, bookId, WorkspacePrincipal.currentEmail(), request.id());
        String message = "Your requested book " + request.title() + " by " + request.authorName()
                + ("ACCEPTED".equals(status) ? " has been accepted and added to the Global Public Library." : " has been rejected by the administrator.");
        long event = jdbc.queryForObject("UPDATE book_event_cursor SET last_id=last_id+1 WHERE singleton=TRUE RETURNING last_id", Long.class);
        jdbc.update("""
                INSERT INTO book_events(id,workspace_id,event_type,payload)
                VALUES (?,?,'public-book-request.reviewed',json_build_object('eventId',?::text,'type','public-book-request.reviewed',
                'schemaVersion',1,'requestId',?::text,'status',?::text,'bookId',?::bigint,'message',?::text,'occurredAt',now())::text)
                """, event, request.workspaceId(), Long.toString(event), request.id().toString(), status, bookId, message);
        jdbc.update("INSERT INTO public_request_emails(request_id,recipient,message) VALUES (?,?,?)", request.id(), request.requesterEmail(), message);
        return find(request.id(), false);
    }
    private PublicLibraryBookRequest find(UUID id, boolean lock) {
        return jdbc.query("SELECT * FROM public_library_book_requests WHERE id=?" + (lock ? " FOR UPDATE" : ""), this::map, id)
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Book request not found"));
    }
    private PublicLibraryBookRequest map(ResultSet rs, int row) throws SQLException {
        var reviewed = rs.getTimestamp("reviewed_at");
        return new PublicLibraryBookRequest(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("author_name"),
                rs.getObject("workspace_id", UUID.class), rs.getString("requester_email"), rs.getString("status"),
                rs.getObject("book_id", Long.class), rs.getString("reviewed_by"), rs.getTimestamp("created_at").toInstant(), reviewed == null ? null : reviewed.toInstant());
    }
}
