package com.parvez.android.document;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class BookReadingMigrationTest {
    @Autowired DataSource dataSource;

    @Test void upgradePreservesExistingBooksAndEnforcesReadingConstraints() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), "5");
            var jdbc = schema.jdbc();
            var books = jdbc.queryForList("SELECT * FROM books ORDER BY id");
            assertFalse(books.isEmpty());
            var events = jdbc.queryForList("SELECT * FROM book_events ORDER BY id");

            migrate(schema.name(), null);
            assertEquals(withoutLegacyIdentifier(books), withoutLegacyIdentifier(jdbc.queryForList("SELECT * FROM books ORDER BY id")));
            assertEquals(events.stream().map(row -> row.get("id")).toList(),
                    jdbc.queryForList("SELECT id FROM book_events ORDER BY id", Long.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM book_documents", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_progress", Integer.class));

            long book = ((Number) books.getFirst().get("id")).longValue();
            long otherBook = ((Number) books.get(1).get("id")).longValue();
            UUID document = seedProgress(jdbc, book);
            assertThrows(DataIntegrityViolationException.class, () -> insertDocument(jdbc, book, UUID.randomUUID(), "reader@example.com"));
            assertThrows(DataIntegrityViolationException.class, () -> insertDocument(jdbc, Long.MAX_VALUE, UUID.randomUUID(), "reader@example.com"));
            assertThrows(DataIntegrityViolationException.class, () -> insertDocument(jdbc, otherBook, UUID.randomUUID(), "missing@example.com"));
            assertThrows(DataIntegrityViolationException.class, () -> insertProgress(jdbc, book, document, "reader@example.com"));
            assertThrows(DataIntegrityViolationException.class, () -> insertProgress(jdbc, otherBook, document, "reader@example.com"));
            assertThrows(DataIntegrityViolationException.class, () -> insertProgress(jdbc, book, document, "missing@example.com"));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE reading_progress SET current_page = 0 WHERE book_id = ?", book));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE reading_progress SET max_page_reached = 1 WHERE book_id = ?", book));

            jdbc.update("INSERT INTO reading_progress_operations (user_email, operation_id, book_id, document_id, current_page, expected_version) VALUES (?, ?, ?, ?, 93, 0)",
                    "reader@example.com", UUID.randomUUID(), book, document);
            jdbc.update("DELETE FROM book_documents WHERE id = ?", document);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_progress", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reading_progress_operations", Integer.class));
            assertEquals(withoutLegacyIdentifier(books), withoutLegacyIdentifier(jdbc.queryForList("SELECT * FROM books ORDER BY id")));
        });
    }

    @Test void referenceIndexesPreservePopulatedReadingData() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), "7");
            var jdbc = schema.jdbc();
            long book = jdbc.queryForObject("SELECT min(id) FROM books", Long.class);
            UUID document = seedProgress(jdbc, book);
            jdbc.update("INSERT INTO reading_progress_operations (user_email, operation_id, book_id, document_id, current_page, expected_version) VALUES (?, ?, ?, ?, 93, 0)",
                    "reader@example.com", UUID.randomUUID(), book, document);
            var documents = jdbc.queryForList("SELECT * FROM book_documents");
            var progress = jdbc.queryForList("SELECT * FROM reading_progress");
            var operations = jdbc.queryForList("SELECT * FROM reading_progress_operations");

            migrate(schema.name(), null);
            assertEquals(documents, jdbc.queryForList("SELECT * FROM book_documents"));
            assertEquals(progress, jdbc.queryForList("SELECT * FROM reading_progress"));
            assertEquals(operations, jdbc.queryForList("SELECT * FROM reading_progress_operations"));
            assertReferenceIndexes(jdbc, schema.name());
            assertEquals(0, flyway(schema.name(), null).migrate().migrationsExecuted);
        });
    }

    @Test void freshInstallAppliesAndValidatesAllMigrations() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), null);
            assertReferenceIndexes(schema.jdbc(), schema.name());
            assertEquals(0, flyway(schema.name(), null).migrate().migrationsExecuted);
        });
    }

    @Test void passwordResetQuotaUpgradePreservesAccountsSessionsAndPendingTokens() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), "14");
            var jdbc = schema.jdbc();
            jdbc.update("INSERT INTO workspace_users(email,password_hash,role) VALUES ('reset-upgrade@example.com','test-only','SUPER_ADMIN')");
            jdbc.update("INSERT INTO password_reset_tokens(email,token_hash,expires_at) VALUES ('reset-upgrade@example.com',?,now()+interval '30 minutes')", "a".repeat(64));
            jdbc.update("INSERT INTO refresh_tokens(token_hash,email,expires_at) VALUES (?,'reset-upgrade@example.com',now()+interval '1 day')", "b".repeat(64));
            var users = jdbc.queryForList("SELECT * FROM workspace_users ORDER BY email");
            var tokens = jdbc.queryForList("SELECT * FROM password_reset_tokens");
            var sessions = jdbc.queryForList("SELECT * FROM refresh_tokens");
            migrate(schema.name(), null);
            assertEquals(users, jdbc.queryForList("SELECT * FROM workspace_users ORDER BY email"));
            assertEquals(tokens, jdbc.queryForList("SELECT * FROM password_reset_tokens"));
            assertEquals(sessions, jdbc.queryForList("SELECT * FROM refresh_tokens"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM password_reset_history", Integer.class));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "INSERT INTO password_reset_history(email,reset_at) VALUES ('missing@example.com',now())"));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "INSERT INTO password_reset_history(email,reset_at) VALUES ('reset-upgrade@example.com',NULL)"));
            jdbc.update("INSERT INTO password_reset_history(email,reset_at) VALUES ('reset-upgrade@example.com',clock_timestamp())");
            assertTrue(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname=?", String.class, schema.name())
                    .contains("password_reset_history_account_month"));
            assertEquals(0, flyway(schema.name(), null).migrate().migrationsExecuted);
        });
    }

    @Test void resetEmailOutboxUpgradePreservesPopulatedTokensAndHistory() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), "15");
            var jdbc = schema.jdbc();
            jdbc.update("INSERT INTO workspace_users(email,password_hash,role) VALUES ('queue-upgrade@example.com','test-only','SUPER_ADMIN')");
            jdbc.update("INSERT INTO password_reset_tokens(email,token_hash,expires_at) VALUES ('queue-upgrade@example.com',?,now()+interval '30 minutes')", "a".repeat(64));
            jdbc.update("INSERT INTO password_reset_history(email,reset_at) VALUES ('queue-upgrade@example.com',clock_timestamp())");
            var tokens = jdbc.queryForList("SELECT * FROM password_reset_tokens");
            var history = jdbc.queryForList("SELECT * FROM password_reset_history");
            migrate(schema.name(), null);
            assertEquals(tokens, jdbc.queryForList("SELECT * FROM password_reset_tokens"));
            assertEquals(history, jdbc.queryForList("SELECT * FROM password_reset_history"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM password_reset_emails", Integer.class));
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO password_reset_emails(id,email,token_hash,encrypted_token,expires_at) VALUES (?,'queue-upgrade@example.com',?,'test-only-ciphertext',now()+interval '30 minutes')", id, "a".repeat(64));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE password_reset_emails SET attempts=-1 WHERE id=?", id));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE password_reset_emails SET lease_id=? WHERE id=?", UUID.randomUUID(), id));
            jdbc.update("DELETE FROM workspace_users WHERE email='queue-upgrade@example.com'");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM password_reset_emails", Integer.class));
            assertEquals(0, flyway(schema.name(), null).migrate().migrationsExecuted);
        });
    }

    @Test void loginHistoryUpgradePreservesPopulatedPasswordAuditReceiptsAndSessions() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), "17");
            var jdbc = schema.jdbc();
            String email = "history-upgrade@example.com";
            UUID workspace = UUID.randomUUID();
            UUID change = UUID.randomUUID();
            jdbc.update("INSERT INTO workspaces(id,name) VALUES (?,'History upgrade')", workspace);
            jdbc.update("INSERT INTO workspace_users(email,password_hash,workspace_id) VALUES (?,'test-only',?)", email, workspace);
            jdbc.update("INSERT INTO password_change_history(id,email,workspace_id,source,browser,device,user_agent,ip_address) VALUES (?,?,?,'CHANGE','Chrome','Computer / Linux','test agent','203.0.113.1')", change, email, workspace);
            jdbc.update("INSERT INTO password_change_emails(id,attempts,failed_at) VALUES (?,2,clock_timestamp())", change);
            jdbc.update("INSERT INTO refresh_tokens(token_hash,email,expires_at) VALUES (?,?,clock_timestamp()+interval '1 day')", "a".repeat(64), email);
            var audit = jdbc.queryForList("SELECT * FROM password_change_history");
            var receipts = jdbc.queryForList("SELECT * FROM password_change_emails");
            var sessions = jdbc.queryForList("SELECT * FROM refresh_tokens");
            migrate(schema.name(), null);
            assertEquals(audit, jdbc.queryForList("SELECT * FROM password_change_history"));
            assertEquals(receipts, jdbc.queryForList("SELECT * FROM password_change_emails"));
            assertEquals(sessions, jdbc.queryForList("SELECT * FROM refresh_tokens"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM login_history", Integer.class));
            var indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname=?", String.class, schema.name());
            assertTrue(indexes.containsAll(java.util.List.of("login_history_recent", "login_history_workspace_recent",
                    "login_history_account_recent", "password_change_history_recent", "password_change_history_workspace_recent")));
            UUID login = UUID.randomUUID();
            jdbc.update("INSERT INTO login_history(id,email,workspace_id,browser,device) VALUES (?,?,?,'Unknown','Unknown')", login, email, workspace);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO login_history(id,email,browser,device) VALUES (?,'missing@example.com','Unknown','Unknown')", UUID.randomUUID()));
            // Both histories retain captured workspace IDs without acquiring workspace FK locks.
            jdbc.update("UPDATE workspace_users SET workspace_id=NULL,role='SUPER_ADMIN' WHERE email=?", email);
            jdbc.update("DELETE FROM workspaces WHERE id=?", workspace);
            assertEquals(workspace, jdbc.queryForObject("SELECT workspace_id FROM login_history WHERE id=?", UUID.class, login));
            assertEquals(workspace, jdbc.queryForObject("SELECT workspace_id FROM password_change_history WHERE id=?", UUID.class, change));
            jdbc.update("DELETE FROM workspace_users WHERE email=?", email);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM login_history", Integer.class));
            assertEquals(0, flyway(schema.name(), null).migrate().migrationsExecuted);
        });
    }

    @Test void appliedV18ValidatesAndForwardUpgradePreservesBothHistoriesAndReceipts() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), "18");
            var jdbc = schema.jdbc();
            assertEquals(22842503, jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='18'", Integer.class));
            String email = "applied-v18@example.com";
            UUID workspace = UUID.randomUUID(), login = UUID.randomUUID(), change = UUID.randomUUID();
            jdbc.update("INSERT INTO workspaces(id,name) VALUES (?,'Applied V18')", workspace);
            jdbc.update("INSERT INTO workspace_users(email,password_hash,workspace_id) VALUES (?,'test-only',?)", email, workspace);
            jdbc.update("INSERT INTO login_history(id,email,workspace_id,browser,device,ip_address,user_agent) VALUES (?,?,?,'Chrome','Computer / Linux','203.0.113.7','existing login')", login, email, workspace);
            jdbc.update("INSERT INTO password_change_history(id,email,workspace_id,source,browser,device) VALUES (?,?,?,'RESET','Firefox','Computer / Linux')", change, email, workspace);
            jdbc.update("INSERT INTO password_change_emails(id,attempts,failed_at) VALUES (?,2,clock_timestamp())", change);
            var logins = jdbc.queryForList("SELECT * FROM login_history");
            var changes = jdbc.queryForList("SELECT * FROM password_change_history");
            var receipts = jdbc.queryForList("SELECT * FROM password_change_emails");
            var appliedHistory = jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
            flyway(schema.name(), "18").validate();
            assertEquals(1, flyway(schema.name(), null).migrate().migrationsExecuted);
            flyway(schema.name(), null).validate();
            assertEquals(logins, jdbc.queryForList("SELECT * FROM login_history"));
            assertEquals(changes, jdbc.queryForList("SELECT * FROM password_change_history"));
            assertEquals(receipts, jdbc.queryForList("SELECT * FROM password_change_emails"));
            assertEquals(appliedHistory, jdbc.queryForList("SELECT * FROM flyway_schema_history WHERE version <> '19' ORDER BY installed_rank"));
            jdbc.update("UPDATE workspace_users SET workspace_id=NULL,role='SUPER_ADMIN' WHERE email=?", email);
            jdbc.update("DELETE FROM workspaces WHERE id=?", workspace);
            assertEquals(workspace, jdbc.queryForObject("SELECT workspace_id FROM login_history WHERE id=?", UUID.class, login));
            assertEquals(workspace, jdbc.queryForObject("SELECT workspace_id FROM password_change_history WHERE id=?", UUID.class, change));
            jdbc.update("DELETE FROM workspace_users WHERE email=?", email);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM login_history", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM password_change_history", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM password_change_emails", Integer.class));
            assertEquals(0, flyway(schema.name(), null).migrate().migrationsExecuted);
        });
    }

    @Test void passwordChangeAuditUpgradePreservesRecoveryStateAndValidatesReceiptOwnership() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), "16");
            var jdbc = schema.jdbc();
            String email = "change-upgrade@example.com";
            UUID resetId = UUID.randomUUID();
            jdbc.update("INSERT INTO workspace_users(email,password_hash,role) VALUES (?,'test-only','SUPER_ADMIN')", email);
            jdbc.update("INSERT INTO password_reset_tokens(email,token_hash,expires_at) VALUES (?,?,now()+interval '30 minutes')", email, "a".repeat(64));
            jdbc.update("INSERT INTO password_reset_history(email,reset_at) VALUES (?,clock_timestamp())", email);
            jdbc.update("INSERT INTO password_reset_emails(id,email,token_hash,encrypted_token,expires_at) VALUES (?,?,?,'test-only-ciphertext',now()+interval '30 minutes')", resetId, email, "a".repeat(64));
            jdbc.update("INSERT INTO refresh_tokens(token_hash,email,expires_at) VALUES (?,?,now()+interval '1 day')", "b".repeat(64), email);
            var accounts = jdbc.queryForList("SELECT * FROM workspace_users");
            var tokens = jdbc.queryForList("SELECT * FROM password_reset_tokens");
            var history = jdbc.queryForList("SELECT * FROM password_reset_history");
            var receipts = jdbc.queryForList("SELECT * FROM password_reset_emails");
            var sessions = jdbc.queryForList("SELECT * FROM refresh_tokens");
            migrate(schema.name(), null);
            assertEquals(accounts, jdbc.queryForList("SELECT * FROM workspace_users"));
            assertEquals(tokens, jdbc.queryForList("SELECT * FROM password_reset_tokens"));
            assertEquals(history, jdbc.queryForList("SELECT * FROM password_reset_history"));
            assertEquals(receipts, jdbc.queryForList("SELECT * FROM password_reset_emails"));
            assertEquals(sessions, jdbc.queryForList("SELECT * FROM refresh_tokens"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM password_change_history", Integer.class));
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO password_change_history(id,email,source,browser,device) VALUES (?,?,'CHANGE','Unknown','Unknown')", id, email);
            jdbc.update("INSERT INTO password_change_emails(id) VALUES (?)", id);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO password_change_emails(id) VALUES (?)", UUID.randomUUID()));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE password_change_history SET source='OTHER' WHERE id=?", id));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE password_change_emails SET lease_id=? WHERE id=?", UUID.randomUUID(), id));
            jdbc.update("DELETE FROM password_change_emails WHERE id=?", id);
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM password_change_history", Integer.class));
            jdbc.update("DELETE FROM workspace_users WHERE email=?", email);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM password_change_history", Integer.class));
            assertEquals(0, flyway(schema.name(), null).migrate().migrationsExecuted);
        });
    }

    @Test void requestEmailUpgradePreservesQueuedDecisionsAndAllowsIndependentAdminReceipts() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), "12");
            var jdbc = schema.jdbc();
            UUID workspace = UUID.fromString("00000000-0000-0000-0000-000000000001");
            jdbc.update("INSERT INTO workspace_users(email,password_hash,workspace_id) VALUES ('requester@example.com','test-only',?)", workspace);
            UUID request = UUID.randomUUID();
            jdbc.update("INSERT INTO public_library_book_requests(id,title,author_name,workspace_id,requester_email) VALUES (?,'Book','Author',?,'requester@example.com')", request, workspace);
            jdbc.update("INSERT INTO public_request_emails(request_id,recipient,message) VALUES (?,'requester@example.com','Existing decision')", request);
            var before = jdbc.queryForMap("SELECT * FROM public_request_emails WHERE request_id=?", request);
            migrate(schema.name(), null);
            var after = jdbc.queryForMap("SELECT * FROM public_request_emails WHERE request_id=?", request);
            before.forEach((key,value) -> assertEquals(value, after.get(key)));
            assertEquals("DECISION", after.get("email_type"));
            assertEquals("Your Booker public library request", after.get("subject"));
            assertNull(after.get("html_message"));
            for (String recipient : java.util.List.of("admin@example.com", "second-admin@example.com"))
                jdbc.update("INSERT INTO public_request_emails(request_id,email_type,recipient,message) VALUES (?,'SUBMISSION',?,'New request')", request, recipient);
            assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM public_request_emails WHERE request_id=?", Integer.class, request));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO public_request_emails(request_id,email_type,recipient,message) VALUES (?,'SUBMISSION','admin@example.com','Duplicate')", request));
            jdbc.update("DELETE FROM public_request_emails WHERE request_id=? AND email_type='SUBMISSION' AND recipient='admin@example.com'", request);
            assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM public_request_emails WHERE request_id=?", Integer.class, request));
        });
    }

    @Test void rabbitOutboxUpgradePreservesPendingMessagesAndGivesEachReceiptAStableIdentity() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), "13");
            var jdbc=schema.jdbc();
            UUID workspace=UUID.fromString("00000000-0000-0000-0000-000000000001"), request=UUID.randomUUID();
            jdbc.update("INSERT INTO workspace_users(email,password_hash,workspace_id) VALUES ('queue-reader@example.com','test-only',?)",workspace);
            jdbc.update("INSERT INTO public_library_book_requests(id,title,author_name,workspace_id,requester_email) VALUES (?,'Queued book','Author',?,'queue-reader@example.com')",request,workspace);
            jdbc.update("INSERT INTO public_request_emails(request_id,recipient,message,html_message,email_type) VALUES (?,'admin@example.com','Existing text','<p>Existing HTML</p>','SUBMISSION')",request);
            var before=jdbc.queryForMap("SELECT * FROM public_request_emails WHERE request_id=?",request);
            migrate(schema.name(),null);
            var after=jdbc.queryForMap("SELECT * FROM public_request_emails WHERE request_id=?",request);
            before.forEach((key,value) -> assertEquals(value,after.get(key)));
            assertNotNull(after.get("id"));assertNotNull(after.get("available_at"));
            assertNull(after.get("published_at"));assertNull(after.get("failed_at"));assertEquals(0,after.get("attempts"));
            assertEquals(0,flyway(schema.name(),null).migrate().migrationsExecuted);
        });
    }

    private java.util.List<java.util.Map<String, Object>> withoutLegacyIdentifier(java.util.List<java.util.Map<String, Object>> books) {
        return books.stream().map(book -> {
            var copy = new java.util.HashMap<>(book);
            copy.remove("isbn");
            copy.remove("library_type"); copy.remove("created_at"); copy.remove("updated_at");
            return (java.util.Map<String, Object>) copy;
        }).toList();
    }

    @Test void identityUpgradePreservesReferencesAndRemovesIdentifierFromEventReplay() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), "8");
            var jdbc = schema.jdbc();
            long book = jdbc.queryForObject("SELECT min(id) FROM books", Long.class);
            UUID document = seedProgress(jdbc, book);
            jdbc.update("INSERT INTO books (workspace_id, isbn, author, title, publication_date) "
                    + "SELECT workspace_id, '1111111111', 'Migration author', 'Migration title', '2026' FROM books WHERE id = ?", book);
            var eventsBefore = jdbc.queryForList("SELECT id, workspace_id, (payload::jsonb #- '{book,isbn}') - 'schemaVersion' AS snapshot FROM book_events ORDER BY id");
            var documentsBefore = jdbc.queryForList("SELECT * FROM book_documents");
            var progressBefore = jdbc.queryForList("SELECT * FROM reading_progress");
            migrate(schema.name(), null);
            assertEquals(documentsBefore, jdbc.queryForList("SELECT * FROM book_documents"));
            assertEquals(progressBefore, jdbc.queryForList("SELECT * FROM reading_progress"));
            assertEquals(eventsBefore, jdbc.queryForList("SELECT id, workspace_id, payload::jsonb - 'schemaVersion' AS snapshot FROM book_events ORDER BY id"));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND table_name = 'books' AND column_name = 'isbn'", Integer.class, schema.name()));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM book_events WHERE payload::jsonb->'book' ? 'isbn' OR payload::jsonb->>'schemaVersion' <> '2'", Integer.class));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO books (workspace_id, author, title, publication_date) SELECT workspace_id, author, title, publication_date FROM books WHERE id = ?", book));
            assertEquals(document, jdbc.queryForObject("SELECT document_id FROM reading_progress WHERE book_id = ?", UUID.class, book));
        });
    }

    @Test void duplicatePairsBlockUpgradeWithoutDeletingBooksOrReferences() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), "8");
            var jdbc = schema.jdbc();
            long book = jdbc.queryForObject("SELECT min(id) FROM books", Long.class);
            seedProgress(jdbc, book);
            jdbc.update("INSERT INTO books (workspace_id, isbn, author, title, publication_date) SELECT workspace_id, '1111111111', author, title, publication_date FROM books WHERE id = ?", book);
            var booksBefore = jdbc.queryForList("SELECT * FROM books ORDER BY id");
            var progressBefore = jdbc.queryForList("SELECT * FROM reading_progress");
            assertThrows(org.flywaydb.core.api.FlywayException.class, () -> migrate(schema.name(), null));
            assertEquals(booksBefore, jdbc.queryForList("SELECT * FROM books ORDER BY id"));
            assertEquals(progressBefore, jdbc.queryForList("SELECT * FROM reading_progress"));
            jdbc.update("UPDATE books SET title = title || ' (other edition)' WHERE isbn = '1111111111'");
            migrate(schema.name(), null);
        });
    }

    @Test void publicLibraryUpgradePreservesPrivateDataAndEnforcesSystemScopes() throws Exception {
        inSchema(schema -> {
            migrate(schema.name(), "9");
            var jdbc = schema.jdbc();
            long privateBook = jdbc.queryForObject("SELECT min(id) FROM books", Long.class);
            seedProgress(jdbc, privateBook);
            var privateBooks = withoutLegacyIdentifier(jdbc.queryForList("SELECT * FROM books ORDER BY id"));
            var documents = jdbc.queryForList("SELECT * FROM book_documents");
            var progress = jdbc.queryForList("SELECT * FROM reading_progress");
            var events = jdbc.queryForList("SELECT * FROM book_events ORDER BY id");
            migrate(schema.name(), null);
            assertEquals(privateBooks, withoutLegacyIdentifier(jdbc.queryForList("SELECT * FROM books ORDER BY id")));
            assertEquals(documents, jdbc.queryForList("SELECT * FROM book_documents"));
            assertEquals(progress, jdbc.queryForList("SELECT * FROM reading_progress"));
            assertEquals(events, jdbc.queryForList("SELECT * FROM book_events ORDER BY id"));
            assertEquals("OWNER", jdbc.queryForObject("SELECT role FROM workspace_users WHERE email = 'reader@example.com'", String.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM books WHERE library_type <> 'PRIVATE'", Integer.class));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO books (library_type, title, author, publication_date) VALUES ('PRIVATE', 'Invalid', 'Author', '2026')"));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO books (library_type, workspace_id, title, author, publication_date) SELECT 'PUBLIC', workspace_id, 'Invalid', 'Author', '2026' FROM books WHERE id = ?", privateBook));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO workspace_users (email, password_hash, role) VALUES ('invalid@example.com', 'test', 'OWNER')"));
            long publicBook = jdbc.queryForObject("INSERT INTO books (library_type, title, author, publication_date) VALUES ('PUBLIC', 'Global', 'Author', '2026') RETURNING id", Long.class);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO books (library_type, title, author, publication_date) VALUES ('PUBLIC', 'Global', 'Author', '2026')"));
            assertEquals(events, jdbc.queryForList("SELECT * FROM book_events ORDER BY id"));
            UUID publicDocument = UUID.randomUUID();
            insertDocument(jdbc, publicBook, publicDocument, "reader@example.com");
            jdbc.update("INSERT INTO public_reading_progress (workspace_id, book_id, document_id, current_page, max_page_reached, version, last_read_at) SELECT workspace_id, ?, ?, 93, 93, 1, now() FROM books WHERE id = ?", publicBook, publicDocument, privateBook);
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE public_reading_progress SET current_page = 145, max_page_reached = 145 WHERE book_id = ?", publicBook));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("UPDATE public_reading_progress SET current_page = 0 WHERE book_id = ?", publicBook));
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO public_reading_progress (workspace_id, book_id, document_id, current_page, max_page_reached, version, last_read_at) SELECT workspace_id, book_id, document_id, current_page, max_page_reached, version, last_read_at FROM public_reading_progress"));
            jdbc.update("DELETE FROM books WHERE id = ?", publicBook);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM public_reading_progress", Integer.class));
            assertEquals(progress, jdbc.queryForList("SELECT * FROM reading_progress"));
        });
    }

    private UUID seedProgress(JdbcTemplate jdbc, long book) {
        jdbc.update("INSERT INTO workspace_users (email, password_hash, workspace_id) SELECT 'reader@example.com', 'test-only', workspace_id FROM books WHERE id = ?", book);
        UUID document = UUID.randomUUID();
        insertDocument(jdbc, book, document, "reader@example.com");
        insertProgress(jdbc, book, document, "reader@example.com");
        return document;
    }

    private void insertDocument(JdbcTemplate jdbc, long book, UUID document, String user) {
        jdbc.update("""
                INSERT INTO book_documents (id, book_id, storage_provider, storage_key, original_file_name,
                    mime_type, file_size, page_count, checksum, source_type, created_by, operation_id)
                VALUES (?, ?, 'LOCAL', ?, 'book.pdf', 'application/pdf', 1024, 144, ?, 'UPLOAD', ?, ?)
                """, document, book, document + ".pdf", "a".repeat(64), user, UUID.randomUUID());
    }

    private void insertProgress(JdbcTemplate jdbc, long book, UUID document, String user) {
        jdbc.update("""
                INSERT INTO reading_progress (user_email, book_id, document_id, current_page,
                    max_page_reached, version, last_read_at) VALUES (?, ?, ?, 93, 93, 1, now())
                """, user, book, document);
    }

    private void assertReferenceIndexes(JdbcTemplate jdbc, String schema) {
        var indexes = jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = ?", String.class, schema);
        assertTrue(indexes.containsAll(java.util.List.of("book_documents_creator",
                "reading_progress_document_book", "reading_progress_operations_book_document")));
    }

    private Flyway flyway(String schema, String target) {
        var configuration = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration");
        if (target != null) configuration.target(MigrationVersion.fromVersion(target));
        return configuration.load();
    }

    private void migrate(String schema, String target) {
        var flyway = flyway(schema, target);
        flyway.migrate();
        flyway.validate();
    }

    private void inSchema(Consumer<TestSchema> test) throws Exception {
        String schema = "reading_migration_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = dataSource.getConnection()) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            String previousSchema = connection.getSchema();
            jdbc.execute("CREATE SCHEMA " + schema);
            try {
                connection.setSchema(schema);
                test.accept(new TestSchema(schema, jdbc));
            } finally {
                connection.setSchema(previousSchema);
                jdbc.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private record TestSchema(String name, JdbcTemplate jdbc) {}
}
