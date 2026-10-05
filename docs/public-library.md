# Public Library backend/API

Existing `/api/books` contracts remain the private library; client sources are maintained separately. Public means shared by authenticated application accounts, not anonymously downloadable.

## Architecture and schema

Flyway V10 extends the existing `books` table with `library_type` (PRIVATE by default), `created_at`, and `updated_at`. Private rows retain a workspace ID; PUBLIC rows have no workspace ID. A database check enforces the distinction. Numeric IDs remain global/stable. Private `(workspace_id, author, title)` uniqueness remains; a partial unique index enforces exact, case-sensitive public author/title uniqueness independently. Private and public books can have the same author/title pair.

The existing `Book` JPA entity, BookRequest/BookResponse, mapper and repository are reused. Private lists, searches, quotas, personal progress and event replay continue to select only the authenticated workspace. Public inserts deliberately skip the private `book.created` trigger/log; there is no new public SSE/push contract.

Existing `book_documents`, FileStorageService, LOCAL provider, PDF validation, immutable replacement, generated storage keys, byte streaming and Range/HEAD handling are reused. The public API cannot resolve private IDs, including for a private owner or Super Admin. Public document IDs passed through private routes are also inaccessible.

`workspace_users.role` is OWNER or SUPER_ADMIN. Existing users default to OWNER with a workspace; Super Admin is a standalone account with no workspace. The same email/password, PBKDF2, Basic and JWT login/refresh/logout mechanisms apply. JWT requests resolve current role from the database; no separate admin token or client-supplied role grants access.

Public progress uses `public_reading_progress`, unique on `(workspace_id, book_id)`, with a composite document/book FK and timestamps. Durable retry receipts use `(workspace_id, operation_id)` in `public_reading_progress_operations`. Both cascade with workspace/book/document deletion. Public progress has supporting book/document/recent-workspace indexes and a validation trigger checking public document ownership and valid current/maximum pages.

The existing ReadingProgressService implements both scopes through a closed internal enum: private account identity versus public workspace identity. SQL table/column names never come from a request. DTOs, percentage math, first-open defaults, revisions, conflicts, completion and operation replay are shared; private behavior is preserved.

## Initial Super Admin setup

Configure both values on the initial launch:

```text
SUPER_ADMIN_EMAIL=system-owner@example.com
SUPER_ADMIN_PASSWORD=<12–64 character secret>
```

`.env.example` and Compose pass these values to the backend. Source/Maven mode requires exporting them explicitly, like existing database/JWT settings. No password is embedded in migrations, code or documentation.

SuperAdminBootstrap provisions after migrations during application startup. A database advisory lock serializes replicas. Email is normalized. Existing Super Admin setup is idempotent and never resets the password; an existing workspace-owner email causes startup to fail rather than silently promoting it. Incomplete/invalid configured credentials also fail startup. An existing installation can start without either value, but has no newly provisioned admin until initial setup is configured. Remove bootstrap secrets from runtime configuration once provisioning is complete; the account persists. There is no public self-service role-promotion endpoint.

Log in through existing `POST /api/auth/login` or registered HTTP Basic. A Super Admin can list/read/manage the public library, but has no workspace progress. Private workspace APIs and public progress/summary APIs return 403 for that standalone account. This intentionally gives public-library management privileges without access to customer private libraries.

## API inventory

Authentication is required for every route below. Workspace identity is derived from the authenticated owner account; no workspace selector is accepted. Response shapes are the existing BookResponse, DocumentResponse, ReadingProgress and Summary. Lists use the existing unpaginated exact-filter convention.

| Method | Path | Authorization | Success | Additional errors |
| --- | --- | --- | --- | --- |
| GET | `/api/public-books` | Workspace user or Super Admin | 200 BookResponse[] | 401 |
| GET | `/api/public-books/{bookId}` | Workspace user or Super Admin | 200 BookResponse | 400 invalid ID; 401; 404 missing/private ID |
| POST | `/api/public-books` | Super Admin | 201 BookResponse + Location | 400 validation; 401; 403 role; 409 duplicate public pair |
| PUT | `/api/public-books/{bookId}` | Super Admin | 200 BookResponse | 400 validation/ID; 401; 403; 404; 409 duplicate pair |
| DELETE | `/api/public-books/{bookId}` | Super Admin | 204; cleanup queued | 400 ID; 401; 403; 404 |
| POST | `/api/public-books/{bookId}/document` | Super Admin | 201 DocumentResponse + Location | 400 filename/header/empty; 401; 403; 404; 415 MIME/unsafe PDF; 503 parser/storage |
| GET | `/api/public-books/{bookId}/document` | Workspace user or Super Admin | 200 DocumentResponse | 400 ID; 401; 404 |
| GET | `/api/public-books/{bookId}/document/content` | Workspace user or Super Admin | 200 PDF or 206 bytes | 400 parameters; 401; 404; 409 replaced pin; 416 Range; 503 storage |
| HEAD | `/api/public-books/{bookId}/document/content` | Workspace user or Super Admin | 200 headers, no body | 400 parameters; 401; 404; 409; 503 |
| GET | `/api/public-books/{bookId}/reading-progress` | Workspace user | 200 ReadingProgress | 400 ID; 401; 403 no workspace; 404 book/PDF |
| PUT | `/api/public-books/{bookId}/reading-progress` | Workspace user | 200 ReadingProgress | 400 invalid page/revision/body; 401; 403 no workspace; 404; 409 revision/version/retry conflict |
| GET | `/api/public-books/reading-summaries` | Workspace user | 200 Summary[] | 400 missing/invalid/>100 IDs; 401; 403 no workspace; 404 any missing/private ID |

Unexpected failures use existing safe 500 ApiError handling. Security/proxy/framework failures can have different/empty bodies. There are no public Google Drive import, archive/restore, version history or workspace-selector endpoints.

### Public book metadata

Create and full update use:

```json
{"title":"Global Reading Guide","author":"System Author","publishedDate":"2026","description":"Shared by all workspaces","completed":false}
```

Title/author: required nonblank, maximum 255 each; exact public pair unique. publishedDate: required nonblank string, max 20. Description: optional/nullable, max 5000. Send completed explicitly. Workspace/library type, numeric ID and credentials are not mutable request fields. Super Admin has no book count quota. Update does not alter PDFs or workspace progress; manual completed remains separate from PDF completion.

Example create response:

```json
{"id":101,"title":"Global Reading Guide","author":"System Author","publishedDate":"2026","description":"Shared by all workspaces","completed":false}
```

Location identifies `/api/public-books/101`. GET list accepts optional exact `author` and `title` filters, combined with AND. No match is `[]`. To show books and current-workspace reading state, list books then use reading-summaries in batches of at most 100 IDs and join by bookId. Books without PDFs return null document/progress in summaries.

### Unlimited Super Admin PDF upload

This endpoint intentionally streams a **raw application/pdf body**, not multipart. It bypasses the servlet multipart file/request limits without weakening private multipart uploads. Required query: `fileName`; required header: UUID `Idempotency-Key`. Authentication/role checks occur before controller reads. The body is streamed through the existing storage interface.

```sh
curl -X POST "$API/api/public-books/101/document?fileName=guide.pdf" \
  -H "Authorization: Bearer $ADMIN_ACCESS_TOKEN" \
  -H "Idempotency-Key: $UPLOAD_UUID" \
  -H 'Content-Type: application/pdf' \
  --data-binary @guide.pdf
```

No normal book count, file size, PDF page count or workspace-storage quota is applied. Physical disk/address limits and infrastructure timeouts still exist; configure reverse proxies to permit the intended public upload sizes. Private BOOK_MAX_FILE_SIZE, BOOK_MAX_REQUEST_SIZE, BOOK_MAX_PAGES and BOOK_WORKSPACE_STORAGE_LIMIT remain unchanged.

Validation still rejects empty/wrong-extension/invalid/encrypted PDFs and unsupported active content/attachments. Filename sanitization, parser admission limits and traversal safety checks remain enabled. A shared HTTP upload gate runs after route authorization and before body reads; capacity overflow returns 503 with Retry-After. Parser admission waits for a bounded configurable duration before the same retryable status. See [resource settings](operations.md#pdf-traffic-and-small-server-deployment). There is no antivirus/process-isolation claim. Retries must reuse the same UUID for the same book/file/admin; like private uploads, replay returns original metadata without comparing new bytes. It may be inactive after later replacement; refetch active metadata.

201 returns the existing DocumentResponse fields: bookId, documentId, fileName, fileSize, mimeType, pageCount, checksum, sourceType=UPLOAD, active, createdAt. It never returns storage paths or keys. Replacement activates a new immutable version only after successful validation/transaction; old files remain retained, and failure preserves the active PDF. All workspace resume/progress views reset logically for the new document, without changing Book.completed.

### Reading/streaming

GET content accepts `documentId` UUID to pin the active version and `download=true` for attachment; otherwise disposition is inline. Range uses normal `Range: bytes=0-65535`. Version mismatch is 409. Headers follow private serving: application/pdf, Accept-Ranges, ETag document UUID, Content-Disposition, no-store and nosniff. HEAD checks content availability, ignores Range and returns full Content-Length. Every full/range/header request is authenticated.

### Workspace progress

Before first save, GET returns currentPage=0, pagesRead=0, progressPercentage=0, resumePage=1, lastReadAt=null, version=0, plus documentId/totalPages. Page zero is an unsaved sentinel, not a writable value.

PUT uses the same body as private progress:

```json
{"documentId":"22222222-2222-4222-8222-222222222222","currentPage":93,"version":0,"operationId":"33333333-3333-4333-8333-333333333333"}
```

Current page must be 1..totalPages; document must be active; version must be explicit, non-null and nonnegative. No percentage is accepted as authority. For 144 pages, reaching 93 gives 64.58%. Maximum page reached drives percentage/completion; currentPage drives resume. Final page means completed; moving backward preserves maximum/completion. All teammates share resume/revision/receipt identity, while another workspace has independent state even for the same public book/document.

200 example:

```json
{"bookId":101,"documentId":"22222222-2222-4222-8222-222222222222","currentPage":93,"totalPages":144,"pagesRead":93,"progressPercentage":64.58,"lastReadAt":"2026-09-30T12:00:00Z","completed":false,"resumePage":93,"version":1}
```

Exact accepted retries do not advance revision/timestamp again; they return current state, potentially newer than the original result. Stale/future revision returns 409 with ReadingProgress. A stale higher maximum can merge without overwriting resume/lastReadAt; a future revision makes no change. Replaced document or changed body under an already accepted operationId returns 409 ApiError. Clients must handle both body shapes. A selected workspaceId/userId/percentage in an extra field/header/query cannot change authenticated ownership.

### Deletion and file lifecycle

The project did not previously use book soft deletion, so public DELETE hard-deletes. A book row lock coordinates deletion with PDF activation/progress writes. Its document/progress/receipt rows cascade; all retained storage keys are first inserted into document_file_deletions in the same transaction. Rollback preserves book/files and leaves no committed cleanup receipt. Readers see 404 after deletion.

DocumentFileCleanup polls committed receipts every 30 seconds in batches of 100 using the existing storage provider. Successful/missing-file deletion acknowledges the receipt; failure keeps it for later retry, including after restarts. Multiple replicas can safely repeat idempotent deletion but LOCAL deployments must share protected storage, as already required. There is no new storage provider. Replacement keeps historical versions until deleting the public book; there is no automatic replacement retention policy. Open streams already in flight may complete, depending on storage/OS semantics.

## Deployment and verification

Back up PostgreSQL and the existing file volume; apply V10 on startup. V1–V9 remain unchanged. Existing private rows gain PRIVATE/OWNER defaults; base metadata, IDs, PDF/progress references and notification snapshots are preserved. V10 adds columns/checks/indexes/tables and replaces only the insert trigger's public-book branch. PostgreSQL DDL/index creation takes locks; schedule upgrades appropriately for a large populated catalogue. There is no down migration; rollback follows the existing backup-based convention.

Use one stable JWT key, stable Drive encryption key where enabled, and shared storage across LOCAL replicas. Super Admin privileges are system-owned credentials, never shared owner defaults. Reverse-proxy public upload limits/timeouts are deployment responsibilities; private limits remain enforced. No live Google OAuth changes are part of this feature.

Backend tests cover Super Admin startup/idempotence/no owner promotion, normal JWT/Basic management, role denial, global listing/details, public/private boundaries, preserved private quotas/events, uncapped public upload versus limited private upload, validation/replacement/retries, Range/HEAD/pinning, shared/isolated workspace progress, 0..100 calculation and completion, invalid pages/revisions/ownership selectors, conflicts/backward navigation/replacement, concurrent updates, summaries, deletion cascades and durable cleanup retries. Migration tests cover populated V9 upgrades and new database constraints. Existing backend regressions must also pass.

Run [the full verification suite](operations.md#testing) against disposable PostgreSQL.
Live Google consent and frontend behavior require separate client/deployment checks.
