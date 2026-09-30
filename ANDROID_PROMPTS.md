# Booker Android implementation prompts

Prepared on 2026-09-29 from this backend source tree and the Android baseline supplied by the project owner. Give this entire file to Gemini, then run the numbered prompts in order. This is an implementation guide, not a claim that Android functionality has been delivered.

## 1. Scope, evidence, and source of truth

The current directory is a **Spring Boot backend**, despite being named `android`. The sibling `../android-app` directory is empty. No Android Kotlin source or Gradle configuration was available to verify. Android MVVM/Compose/Coroutines/Flow/Retrofit/OkHttp SSE, package `com.parvez.booker`, file names, and the previous six passing tests are **user-provided context**, not independently verified findings. Prompt 01 must inspect the actual Android checkout before editing it. Do not recreate an app if the existing source is merely missing.

Backend source inspection confirms Java 25, Spring Boot 4.1.1, PostgreSQL, Flyway V1–V8, Spring Security, JPA for books, JdbcTemplate for accounts/documents/progress/Drive, PDFBox 3.0.8, and springdoc 3.1.0. The sibling Angular package declares Angular 22.2 and PDF.js 6.3.289; its `src/app/reading/document-api.service.ts` agrees with the document/progress API routes. Angular is a reference client, not the target of these Android prompts.

A live `/v3/api-docs` download was attempted but the local socket was unavailable in this execution environment. Appendix A is therefore a **hand-authored, source-derived OpenAPI 3.0.3 client contract**, not a captured server specification. Fetch the deployed spec before implementation and compare it with source and this guide. Framework-generated documentation can omit error alternatives or infer multipart/binary types imperfectly: verify against controllers and tests rather than blindly generating the whole client. No backend or Android test suite was executed for this documentation-only task.

### Backend files to read when resolving a contract question

Paths below are relative to the backend root:

| Concern | Authoritative files |
| --- | --- |
| Build/configuration | `pom.xml`, `src/main/resources/application.properties`, `compose.yaml`, `README.md` |
| Authentication | `src/main/java/com/parvez/android/auth/AuthController.java`, `auth/TokenService.java`, `config/SecurityConfig.java` |
| Workspace/account | `saas/WorkspaceAccounts.java`, `saas/WorkspacePrincipal.java` under the same Java package root |
| Catalogue | `model/Book.java`, `dto/BookRequest.java`, `dto/BookResponse.java`, `repository/BookRepository.java`, `service/BookService.java`, `controller/BookController.java` |
| Document/version/storage | `document/BookDocument.java`, `BookDocumentController.java`, `BookDocumentService.java`, `BookDocumentRepository.java`, `BookAccess.java`, `PdfInspector.java`; `storage/FileStorageService.java`, `LocalFileStorageService.java` |
| Progress/conflicts | `reading/ReadingProgress.java`, `ReadingProgressController.java`, `ReadingProgressService.java` |
| Drive | `drive/GoogleDriveController.java`, `GoogleDriveConnectionService.java`, `GoogleDriveImportService.java`, `GoogleDriveGateway.java`, `DriveCredentialCipher.java`, `GoogleDriveSettings.java` |
| Errors/OpenAPI | `dto/ApiError.java`, `exception/GlobalExceptionHandler.java`, `config/OpenApiConfig.java` |
| SSE | `controller/BookNotificationController.java`, `notification/BookEventStream.java`, `BookEventStore.java`, `docs/book-notifications.md` |
| Schema | `src/main/resources/db/migration/V1__create_book_table.sql` through `V9__book_author_title_identity.sql` |
| Contract tests | `src/test/java/com/parvez/android/auth/TokenAuthenticationTest.java`, `saas/WorkspaceIsolationTest.java`, `document/BookReadingIntegrationTest.java`, `BookDocumentHeadTest.java`, `BookDocumentUploadFailureTest.java`, `BookReadingMigrationTest.java`, `reading/ReadingCompletionTest.java`, `drive/GoogleDriveIntegrationTest.java`, `GoogleDriveGatewayTest.java` |
| Existing design/deployment notes | `docs/book-reading.md`; use implementation when historical phase notes disagree |

### Verified architecture and data model

Book create/response fields are title, author, publishedDate, description and
completed (plus numeric id in responses). The exact author/title pair is unique
per workspace; individual authors and titles may be reused. Use schemaVersion 2
for book.created events and numeric ID lookup for book details.

- Books belong to a workspace, not directly to an individual. `books.id` is a generated BIGINT identity; use Kotlin `Long`, never a UUID for document URLs. Workspace IDs and document/import/operation IDs are UUID strings.
- `workspace_users.email` is the account primary key, normalized by stripping surrounding whitespace and lowercasing. There is no separate user UUID API. Workspace and account tables are accessed through JDBC, not separate JPA User/Workspace entities. Authentication resolves current workspace/account server-side. Never send a trusted workspace/user ID from Android.
- V4 introduces workspaces/accounts, V5 refresh tokens, V6 documents/progress/operation receipts, V7 Drive connection/OAuth/import tables, V8 additional foreign-key indexes, and V9 workspace author/title identity plus event schemaVersion 2. Existing migrations remain immutable.
- One active immutable document per book is enforced by a partial unique index. Replacement creates a UUID version, retains old bytes/metadata, and changes the active version atomically. There is no version history/download-old-version/delete-document API.
- `book_documents` stores provider, opaque key, original filename, MIME, size, parsed page count, SHA-256, source, uploader, retry operation, active flag, timestamps. Only public document fields appear in responses. Binaries live in protected local storage, not PostgreSQL. An abstraction exists; S3/signed URLs do not exist yet.
- `reading_progress` has primary key `(user_email, book_id)` and references document+book, with current page, maximum page reached, revision, server last-read time. Reading receipts have primary key `(user_email, operation_id)`. Total pages comes from the active document; percentage/completion/resume are derived server-side.
- `Book.completed` remains a manual creation-time workspace metadata field. Personal PDF completion is separate. No API edits Book.completed after creation.
- Google refresh credentials are encrypted with account-bound AES-GCM. Import jobs persist in PostgreSQL and run through a scheduled worker. Imported files use the same PDF pipeline.

### Corrections to the old Android baseline

1. Basic authentication still works, but JWT login, rotating refresh and logout now exist. New Android work should use Bearer auth while preserving existing features.
2. Signup does not issue tokens. After 201, call login, then workspace and catalogue.
3. Workspace JSON has only `id`, `name`, `plan`, `book_limit`, `books_used`. It does **not** contain owner email or remaining capacity. Derive remaining book capacity for display; keep verified login email in account state.
4. `publishedDate` is required/nonblank, maximum 20 characters. It is a string, not necessarily an ISO date.
5. Upload needs UUID `Idempotency-Key`. Saving progress needs all four fields: `documentId`, `currentPage`, `version`, `operationId`. `{currentPage:93}` alone is invalid.
6. Percentage is based on **maximum page reached**, not current resume page. `pagesRead` is that maximum, not a count of individually viewed pages.
7. There is no last-write-wins-by-client-clock API. Use revision conflicts described below.
8. SSE only announces `book.created`; no document, import, or progress events exist. Refresh summaries explicitly.
9. There is still no catalogue PUT/DELETE, book GET-by-ID, server pagination, full-text search, billing, password reset, email verification, team invite, or push registration API. Do not invent these.
10. Google OAuth browser binding and Google Picker currently target a browser flow. A native handoff API is **not implemented**.

## 2. Exact API quick reference

Use origin `http://10.0.2.2:8080` for Android emulator development, HTTPS deployment origin in release. With Retrofit base URL `http://10.0.2.2:8080/api/`, annotations are relative (`books`, `auth/login`), without a leading slash or another `api/`. Health/spec use the origin root, not `/api/actuator/health` or `/api/v3/api-docs`. Physical devices require a reachable configured host.

Protected routes accept `Authorization: Bearer <accessToken>` or legacy Basic. Public auth requests should use a separate client without automatic bearer injection/refresh. JSON bodies use `application/json`. No workspace header. Each table row names actual behavior, not a planned endpoint.

| Method | Full path | Request | Success | Important failures |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/signup` | workspaceName, email, password | 201 SignupResponse | 400 validation, 409 duplicate |
| POST | `/api/auth/login` | email, password | 200 Tokens | 400, 401 |
| POST | `/api/auth/refresh` | refreshToken | 200 replacement Tokens | 400, 401 invalid/consumed/expired |
| POST | `/api/auth/logout` | refreshToken | 204 | 400, 401 malformed/expired token |
| GET | `/api/workspace` | none | 200 Workspace | 401 |
| GET | `/api/books` | optional exact author/title query | 200 Book[] | 401 |
| POST | `/api/books` | BookRequest | 201 Book + Location to numeric book-ID lookup | 400, 401, 403 book quota, 409 duplicate author/title pair |
| GET | `/api/books/{bookId}` | numeric bookId | 200 Book | 401, 404 |
| GET | `/api/books/events` | optional Last-Event-ID | 200 text/event-stream | 400 cursor, 401, 503 capacity |
| POST | `/api/books/{bookId}/document` | multipart part `file`; UUID Idempotency-Key | 201 Document + Location, including replay | 400, 401, 403 storage quota, 404, 413, 415, 503 |
| GET | `/api/books/{bookId}/document` | none | 200 active Document | 401, 404 |
| GET | `/api/books/{bookId}/document/content` | optional documentId UUID, download boolean; Range header | 200 PDF or 206 bytes | 400, 401, 404, 409 replacement, 416, 503 |
| HEAD | `/api/books/{bookId}/document/content` | same queries; ignores Range | 200 headers, no body | 400, 401, 404, 409, 503 |
| GET | `/api/books/{bookId}/reading-progress` | none | 200 ReadingProgress | 401, 404 |
| PUT | `/api/books/{bookId}/reading-progress` | ProgressUpdate | 200 ReadingProgress | 400, 401, 404, **409 ReadingProgress OR ApiError** |
| GET | `/api/books/reading-summaries` | `bookIds=1,2,3`, 1–100 IDs | 200 ReadingSummary[] | 400, 401, 404 if any inaccessible |
| POST | `/api/integrations/google-drive/connect` | empty body | 200 authorizationUrl + binding cookie | 401, 503 disabled |
| GET | `/api/integrations/google-drive/callback` | browser state, code, cookie | 200 HTML | 400, 403, 503 |
| GET | `/api/integrations/google-drive/connection` | none | 200 connected boolean | 401 |
| DELETE | `/api/integrations/google-drive/connection` | none | 204 | 401 |
| GET | `/api/integrations/google-drive/picker` | none | 200 accessToken, apiKey, appId | 401, 403 grant revoked, 409 disconnected, 503 |
| POST | `/api/books/{bookId}/document/imports/google-drive` | UUID Idempotency-Key; JSON fileId | 202 ImportStatus + Location | 400, 401, 404, 409 |
| GET | `/api/books/{bookId}/document/imports/{importId}` | import UUID | 200 ImportStatus | 400, 401, 404 |
| GET | `/actuator/health` | public | health JSON | availability-dependent |
| GET | `/v3/api-docs` | public | generated OpenAPI JSON | availability-dependent |

Framework/security/proxy errors may be empty, HTML, or framework JSON rather than ApiError. Parse defensively and never display raw HTML, secrets, stack traces, or unbounded response bodies. ApiError has `dateTime` (local datetime without guaranteed offset), `status`, `error`, `message`, `path`; there is no field-errors map. Progress/document timestamps are UTC Instants.

### Document and progress rules that MUST survive implementation

- Default limits: 200 MB file, 201 MB multipart request, 20,000 pages, 5 GB total workspace document storage including retained versions. These are deployment settings, not constants discoverable through an API; there is no limits endpoint. Client checks are advisory; server errors are authoritative.
- Upload must provide a `.pdf` filename and `application/pdf` part MIME. Backend checks signature/parser/safety, rejects encrypted or unsafe PDFs, sanitizes filename, and generates storage keys. Empty PDF is 400, oversized is 413, invalid/unsafe PDF is 415. Failed replacement keeps the previous active document.
- Upload retry key is scoped to book+account; the server returns the original document for that key **without checking that retry bytes match**. Android must never reuse a key for different bytes/file selections. A replay may return an inactive historical document if another replacement happened; always re-fetch active metadata after success. Upload cancellation/timeouts do not prove the server rolled back.
- Content is authenticated, `application/pdf`, `Cache-Control: no-store`, `Accept-Ranges: bytes`, ETag equal to quoted document UUID, safe Content-Disposition (inline by default, attachment with download=true), `nosniff`. Pin `documentId` on every download/range. Metadata is JSON; `/content` is bytes. HEAD checks authorization/version/storage and complete length without reading bytes. Do not assume If-Range behavior merely because CORS allows its header; test it before use.
- First-open progress for an existing PDF: currentPage=0, pagesRead=0, resumePage=1, version=0, percentage=0, completed=false, lastReadAt=null. No progress row is created by GET. PUT accepts pages 1..totalPages; never PUT zero.
- Successful save advances version, sets server lastReadAt, changes currentPage, and keeps max(previous maximum, new page). Percentage is maximum/total*100 rounded HALF_UP to two places. completed is maximum==total, not percentage==100. Navigating backward preserves completion/maximum. Replacement resets the active-document view of personal progress.
- Exact replay of an accepted operation returns 200 with **current server progress**, which may be newer than the original response; it does not reapply an old resume position. Reusing operationId with changed book/document/page/version gives 409 ApiError.
- Stale version: 409 ReadingProgress; server preserves current resume/lastReadAt but can merge a larger submitted page into the maximum and increment revision. A 409 can therefore mutate maximum progress. Future version: 409 current progress without mutation. Replaced document: 409 ApiError. Do not treat every 409 as duplicate author/title pair or silently overwrite the server.
- Summaries use at most 100 requested IDs before deduplication; deduplicate then chunk. No-document entries have document=null, progress=null. Map by bookId, not array order.

### Conflict example

Device A saved page 80 at revision 1. Device B saved page 85 at revision 2. A offline now has page 93 with expected revision 1. Its PUT returns 409: server resume stays 85, maximum may become 93, revision advances to 3, lastReadAt stays B's time. Ask whether to resume server page 85 or explicitly keep local page 93. Choosing local creates a **new** operation with page 93 and returned revision 3. Choosing server acknowledges/discards the local intent. Never silently retry page 93 against a new revision. Preserve local pending state across crashes and 401 until same-account reauthentication or explicit discard.

## 3. Shared instruction to paste before every numbered prompt

```text
You are implementing the existing Booker Android application. Read ANDROID_PROMPTS.md and applicable AGENTS.md files. Implement ONLY the numbered prompt requested in this turn, building on earlier completed steps. First inspect actual source; preserve MVVM, Compose, Coroutines/Flow, Retrofit/OkHttp, existing navigation, theme and dependency patterns. Do not rewrite architecture or add libraries without a demonstrated need. Before changing an existing class, explain the specific change and why. Never invent backend routes, response fields, push events, limits APIs or native OAuth support. Use Appendix A plus actual backend source/live OpenAPI; identify discrepancies before dependent changes. Protect account/workspace isolation across requests, local files and async callbacks. Add focused behavioral tests, run the appropriate checks, and report changed files, tested behavior, failures and remaining dependencies. Do not claim unrun tests passed. Do not change backend/Angular code during Android-only steps. Never put credentials in URLs/logs/source. Stop at the requested step; do not silently implement later steps.
```

## 4. Ordered implementation prompts

### Prompt 01 — Inspect the real Android project (read-only confirmation gate)

```text
Inspect the whole Android project and backend contract before modifying code. Locate package com.parvez.booker, AGENTS.md, Gradle/version catalog, min/target/compile SDK, manifest/network security, dependencies, tests, DI, navigation, DTO serializer, BookApi, RetrofitClient, BookRepository, CursorStorage, BookSseManager, BookViewModel/BookUiState, LoginDialog, library/details/create UI, and notifications. Check whether any PDF/local database/token persistence already exists.
Compare actual code with the owner-supplied baseline and this guide. Produce architecture summary, exact file map, supported/backend-missing feature matrix, proposed local data model, API mapping, private-file storage design, and ordered list of likely changed files. Confirm Kotlin Long for bookId/fileSize/version and nullable response fields. Confirm authentication and every Android request's resolved URL.
If source is unavailable, say precisely what is missing; do not generate a replacement app. Make no implementation changes yet. Wait for owner confirmation before Prompt 02, as required by Phase 1 of AGENTS.md.
```

### Prompt 02 — Network contracts, DTOs, and errors

```text
After the Prompt 01 confirmation, extend the current DTO/network/repository structure. Add Tokens/Login/Refresh, Document, ReadingProgress, ProgressUpdate, ReadingSummary, DriveConnection/Picker/ImportStatus models using the existing JSON converter. Use the current Book DTO without the retired identifier; detail lookup is GET /api/books/{bookId}. Other response shapes remain unchanged. Preserve explicit version=0 serialization and nullable description/lastReadAt/document/progress/import documentId/message. Add Retrofit methods for the exact existing routes in Appendix A, including multipart Idempotency-Key, @Streaming content ResponseBody, HEAD, batched bookIds, and progress Response<ReadingProgress> so 409 error bodies can be inspected.
Separate public auth client from authenticated API/SSE/download clients. Normalize base URL once. Route errors into typed outcomes: ApiError, progress conflict, unauthorized, retryable failure, and safe unknown failure; body decoding must use the configured serializer, not substring matching. Consume/close response/error bodies. Do not build screens or add pretend native OAuth routes.
Tests: wire JSON field names/nulls/Long values, explicit version zero, correct URLs/no double api, multipart file part/header, binary streaming, empty 204, and both 409 response shapes. Provide fixtures matching Appendix A.
```

### Prompt 03 — JWT session lifecycle and safe refresh

```text
Replace the Android Basic-only sign-in workflow with POST auth/login, then GET workspace and books. Signup 201 is followed by login; preserve account-already-created state if that login fails. Add a session coordinator shared by Retrofit, SSE and downloads. Refresh tokens rotate: persist the replacement pair atomically before retrying dependent work. Enforce single-flight refresh across concurrent requests, recognize when another request already replaced the token, cap authentication retries, and use a refresh client that cannot recursively authenticate or deadlock the same executor. Distinguish auth rejection from transient refresh network/503 errors.
Attach Bearer only to the configured trusted API origin. No password storage, credential logging, query tokens or authorization forwarded to arbitrary redirect hosts. In-memory session remains the default. If adding explicit Remember me, use a reviewed Android Keystore-backed design compatible with the actual SDK and exclude tokens from backup; do not blindly introduce deprecated persistence APIs. Without remembered login, background jobs defer until reauthentication after process death. A lost refresh response may consume the old token; handle re-login instead of an infinite refresh loop.
On logout, attempt auth/logout with the latest refresh token, then clear local session regardless of network result. Explain server access JWTs remain valid until expiry. Serialize logout against refresh and block late old-session callbacks using a session generation. Cancel SSE/network/jobs and hide account-scoped UI immediately. Keep pending offline progress inaccessible to other accounts; offer explicit discard on logout rather than silently losing unsynced reading. Restore it only after verifying the same account/workspace. Do not fall back silently to Basic after a Bearer failure.
Tests: signup/login, invalid credentials, ten simultaneous 401s causing one refresh, token rotation, transient failure, consumed refresh, logout during refresh, and account switch during a delayed response.
```

### Prompt 04 — Preserve catalogue, workspace, SSE, and notifications

```text
Integrate the session coordinator with the existing catalogue and BookSseManager. Preserve exact author/title query semantics (omit blank filters), numeric book-ID lookup, book creation and quota messages. publishedDate is required. Book.completed remains creation-time metadata; no update/delete controls. Keep local text search labeled/implemented consistently with existing UI, never claim backend full-text search.
SSE remains GET books/events with Accept text/event-stream and Last-Event-ID. Persist opaque cursor strings only after successful event processing; ready has {} with a cursor, book.created has the BookEvent envelope. Scope cursors/dedupe by API environment+normalized account+workspace; no token-derived key. Upsert duplicate REST/SSE creations once. Handle invalid cursor by a catalogue resync and a deliberate fresh subscription/replay policy; do not create a reconnect gap that loses new books. Retain backoff/jitter, five-minute server close, foreground lifecycle, one stream per session and token refresh on 401. Cancel stale collectors on logout. Do not reconnect indefinitely on permanent auth failure.
Keep native notifications and permission behavior, avoid duplicate notifications on replay/own REST creation according to existing UX. Do not promise delivery while the app is killed: backend has no FCM integration. No document/progress SSE events exist.
Tests: ready, replay, duplicates, bad/future cursor, 401 refresh, 503, foreground/background, account changes and notification permission denied.
```

### Prompt 05 — Durable local reading state and account isolation

```text
Implement a focused local store using the project's existing persistence approach. If none can support atomic durable operations, justify a small suitable addition before choosing it; do not add a whole architectural framework. Key state by API origin/environment, normalized verified account email, workspace UUID, book Long and document UUID. Store acknowledged server progress separately from latest local resume intent, local maximum intent, immutable in-flight request body/operation UUID, and next unsent intent. Store document metadata and a small authorized catalogue cache sufficient to reopen an offline book after process recreation for the same account.
Persist page changes promptly and atomically before relying on lifecycle network callbacks. Allow one in-flight mutation per account/book. A newer page must not mutate an uncertain operation's body or revision. Track enough state to avoid erasing newer local navigation when an older response arrives. If local furthest page exceeds the latest local resume page, preserve both; the API has no writable pagesRead field, so sync the maximum then resume as separate ordered operations when necessary, handling conflicts at each step.
Define session lock versus explicit account-data deletion, cached-file quota/eviction, exclusion from backup, disk failure, schema migration, and process recovery. Never store passwords or PDF binary in the local progress database. Keep an offline library locked until the user's established local-session policy permits access; no cross-account fallbacks. No background sync without usable credentials.
Tests: atomic recovery, process recreation, two accounts in one workspace, two environments with same bookId, latest local intent surviving old responses, max 93 followed by resume 20 offline, and disk write failure.
```

### Prompt 06 — Select and upload/replace a PDF

```text
Add device PDF selection using the existing Compose Activity Result pattern and ACTION_OPEN_DOCUMENT/application/pdf. Use ContentResolver; never infer a filesystem path from a content URI. Take persistable read permission when available and handle revoked/moved/deleted/cloud-provider documents. Query display name and nullable size safely. No broad storage permission. Validate advisory filename/MIME/known size; report server limits/errors without claiming 200MB is immutable.
Create a durable upload operation before sending. Stream multipart file from a reproducible source with bounded buffers and Long counters; show throttled progress or indeterminate progress for unknown length. For provider content that can change or cannot be reopened reliably, stage a bounded app-private snapshot to ensure identical retry bytes and clean it up when safe. Do not use readBytes(), ByteArray or Base64 for full PDFs. Do not emit fake network progress while computing content length. UI distinguishes transferring from server validation after 100% bytes sent.
Reuse the same UUID Idempotency-Key and identical file for uncertain network retries; new file or intentional replacement gets a new UUID. Handle cancellation without asserting server rollback. Prevent duplicate taps. On 201 fetch active document, progress and affected summary (replayed document might be inactive); keep the old document usable on upload failure. Explain replacement resets personal active-document progress and retained versions consume quota. Only refresh book quota when relevant; file storage quota is a different limit.
Tests: success, exact retry after lost response, immutable source, new-file/new-key, MIME/extension/empty/413/415/403/503, unknown size, cancellation, provider permission loss, process recreation and account switch. No automatic infinite retries or new key per retry.
```

### Prompt 07 — Secure PDF download and offline file cache

```text
Implement a private document download/cache repository. Fetch active metadata and personal progress, ensure matching documentIds, then GET books/{bookId}/document/content?documentId=<UUID>. Download with @Streaming/OkHttp to an app-private .part file using bounded buffers off the main thread. Never ResponseBody.bytes(). Verify expected size and SHA-256 against metadata before atomic promotion to a complete readable file. Names must be generated/cache keys, not server filenames used as paths. Implement disk space checks, cancel/progress, eviction without deleting an open file, and cleanup of abandoned partial files.
Cache identity includes environment/account/workspace/book/document. PDF HTTP responses are no-store: do not use a shared HTTP cache; explicitly downloaded offline files are a separate private app feature with a clear user policy and removal controls. Do not export tokens or PDF URLs to external viewers. Revalidate active document online; while offline label cached version and preserve local page. Upon replacement isolate old queued progress; never transplant it onto the new PDF.
Support resumable byte downloads only with tested validation: request Range bytes=<partialLength>-, pin documentId, inspect 206 Content-Range and total/ETag before append; 200 means truncate/restart, not append; handle 416 by reconciling expected size/hash or restarting. Always verify final checksum. A 409 requires active metadata refresh and user-visible replacement state. HEAD is optional and bodyless; lengths use Long. On 401 use the shared coordinator; 404 removes access from online UI; 503 retries without corrupting the cached complete file. Authorization headers stay on the trusted backend origin.
Tests: complete/partial/truncated/corrupt response, range ignored with 200, wrong Content-Range, 416, replacement during download, low disk, logout mid-download, missing bytes and offline reopen.
```

### Prompt 08 — Compose PDF reader and saved-page restoration

```text
Reuse any adequate existing PDF reader. If none exists, implement a focused renderer adapter using platform PdfRenderer when supported by the project's min SDK; inspect current official documentation before choosing an alternative dependency. PdfRenderer needs a seekable file descriptor, so open only a verified complete local PDF from Prompt 07. This initial approach downloads before reading; do not claim instant remote range rendering. Range support can resume downloads independently.
Add a reader destination following existing navigation, ReaderViewModel/state and focused renderer lifecycle. API pages are 1-based; renderer indices are 0-based. Restore same-document local unsynced page when present, otherwise server resumePage, otherwise 1 for first-open. Do not reset to 1 due to a failed progress fetch. Avoid saving a default page before restoration completes; mark actual displayed/user-navigation state deliberately. SavedStateHandle alone is not durable pending progress.
Render bounded visible pages/bitmaps off the main thread; serialize renderer access where required, close page handles before renderer, close descriptors, cancel obsolete renders and cap bitmap dimensions during zoom. Provide previous/next/page entry, current/total, zoom/pan, loading/retry, offline and unsynced states. Persist the local page immediately through Prompt 05. Rotation/background/back must retain page; do not pass bitmap/file streams in navigation arguments. Validate renderer page count against document metadata and handle mismatch/corruption safely. Keep PDF personal completion distinct from Book.completed.
Test 144-page fixture restoration at 93 (index 92), first-open at 1, backward reading, rotation/process recreation, rapid navigation, very large pages/files, corrupt/encrypted rejection, descriptor cleanup and accessibility labels/large fonts. Do not claim selectable text/search if the chosen renderer cannot provide it.
```

### Prompt 09 — Debounced progress sync, offline recovery and conflicts

```text
Implement the deterministic outbox from Prompt 05 with exact backend CAS semantics. Debounce page-change sync around 1.2 seconds, coalesce unsent intentions, attempt flush on reader exit/background and reconnect, and persist before network attempts. Lifecycle flush is best effort; process-death reliability comes from durable local state. Integrate existing scheduler or justified WorkManager for deferrable connectivity work, not an always-on service. Workers must verify current account/session and serialize with foreground sync; do not send after logout.
Each PUT body is immutable {documentId,currentPage,version,operationId}; use version 0 explicitly for first save. Network uncertainty keeps the exact body/key for retry. On 200 consume current returned progress while preserving any newer local intent; do not assume response equals the original page. Send later intent with returned revision and a new UUID. Persisted percent/completed are server authoritative; pending local position is labeled unsynced, not silently presented as acknowledged percentage. Preserve offline maximum and resume separately; if 93 then 20 was read offline, sync maximum intent before final resume as needed.
On 409 parse ReadingProgress versus ApiError structurally. A stale-revision ReadingProgress may already merge maximum; store the returned revision/summary and pause automatic resume overwrites. Show server/local page choice. Use server choice to settle local intent; local choice sends a new operation using returned revision. If another conflict happens, handle it again without an infinite loop. Replaced-document ApiError triggers metadata/progress refresh and explicit reopen, never remap old pending pages. Operation-reuse errors are integrity faults, not generic retry prompts. Future revision is also a conflict. Server timestamps, not device clock, decide lastReadAt.
Tests: 93/144=64.58, first-open version0, exact retries, lost 200 response followed by another device update, later local page during an in-flight request, stale merge preserving resume/time, future revision, both 409 shapes, operation reuse, document replacement, offline 93->20, two device conflict choice, two workers, expired session and app kill. Reaching final page then going backward stays completed; rounded 100% alone does not mean completion.
```

### Prompt 10 — Book list summaries and reading UX

```text
Add document/progress summaries to existing library cards using the current Book DTO or creating per-card network calls. Deduplicate book IDs, chunk into at most 100, skip empty calls, limit concurrency and map results by bookId. Preserve catalogue even when summary fetching fails; unknown/error is not 'no PDF'. Refresh after upload/import/save, foreground resume and explicit refresh because SSE has no reading events. Respect filters and account/session generation when responses arrive.
For document=null show Upload PDF and available Drive entry. With a document show Continue from resumePage/Open, current/total, server percentage, optional pagesRead and formatted lastReadAt, plus a collapsed upload/replace action. Keep manual metadata completion visually separate. For never opened show Not started (resume 1), and for pending local state show page and Unsynced. Use backend numbers; formatting 64.58 to 64.6 for display is fine, recomputing persisted progress from currentPage is not. Derive remaining book capacity from book_limit/books_used; do not invent document storage usage fields.
Tests: mixed missing/present documents, summary errors, >100 books, chunk isolation/error handling, no N+1 fetch pattern, backward reading with high maximum, replacement and accessibility/theme consistency.
```

### Prompt 11 — Google Drive integration boundary and usable browser flow

```text
Inspect GoogleDriveController, ConnectionService, the deployed Angular Google Picker flow, and current official Google OAuth restrictions before writing Android OAuth code. The existing POST connect sets booker_drive_binding as HttpOnly, SameSite=Lax, callback-path cookie in the requesting browser. Retrofit's cookie jar is not the Custom Tab's cookie jar. Opening authorizationUrl after a Retrofit call will lose the required browser binding. A native Google access token is not accepted by an existing backend endpoint. Picker is a web API, not a native file-list endpoint. Do not embed Google login in a WebView, weaken state/cookie checks, put Booker tokens in browser URLs or ship client secrets.
Implement the route that works with existing infrastructure: a configured HTTPS Booker web application link opened in an external browser/Custom Tab. The user signs into that web app independently with the same Booker account, connects Drive and selects/imports a PDF using the existing same-origin browser flow. Android should explain this handoff and refresh connection/document/progress/summaries on return. Inspect the deployed Angular route before constructing a book-specific URL; never invent one or rely on an unverified app callback. If the web app has no compatible deployed mobile-browser flow, show a precise unavailable state instead of a fake connected success. Test real consent/Picker in deployment before claiming it works.
Also let the normal Android document picker expose a Google Drive DocumentsProvider when installed: that streams a selected URI to the ordinary upload API, sourceType=UPLOAD. Label it correctly; it is not the backend GOOGLE_DRIVE OAuth import integration and is not guaranteed to be installed.
Add authenticated connection status/disconnect and backend ImportStatus repository support. Do not expose manual arbitrary URL imports. For a fileId obtained from a verified authorized selector flow, POST the existing import endpoint with one durable UUID and persist/poll its Location using trusted relative URLs. No native selector is implied by these methods. Existing statuses are only PENDING/RUNNING/COMPLETED/FAILED. COMPLETED refreshes active metadata/progress; FAILED displays safe message. A failed job needs explicit new operation to restart; same key retrieves the old status. Poll with bounded backoff, pause on background, restore known job after recreation. Backend retries transient Google failure itself (30s/60s, ordinary maximum three attempts); do not create duplicate jobs while pending. Disconnect preserves PDFs, cancels pending jobs as FAILED, but running jobs may finish. Status is private to initiating account.
Tests: connection false/disabled, browser return without success, different browser account, disconnect, pending/running/completed/failed, repeat key, process recreation, hidden other-account job, safe Location, revoked Google grant, cancelled consent and missing DocumentsProvider. Clearly report whether full browser flow is deployed and tested.
```

### Prompt 12 — Optional seamless native Drive handoff: backend prerequisite, not an existing API

```text
Run this step only if the owner requires seamless Android Drive selection instead of Prompt 11's browser flow and has explicitly authorized backend work. Produce the backend/browser/native handoff design first, following AGENTS.md's design/confirmation gates. Do not add Retrofit stubs for nonexistent routes or pretend an Android-only change can bypass browser binding.
Design a short-lived single-use authenticated handoff bound to Booker account, book, allowed return URI and app proof (for example an app-held verifier/challenge), redeemed by the browser to establish its own binding. Review login-CSRF/account confusion: a leaked handoff URL alone must not silently link an attacker's Booker account to a victim's Drive. Prefer an explicit authenticated same-account browser confirmation or equivalent reviewed binding. Keep browser sessions/Picker scoped to the handoff; do not place long-lived API/Google tokens in URLs. Reuse backend state expiry/PKCE/encrypted Google credentials/selected-file permissions and authorize the chosen book at every transition. Restrict redirects, suppress referrer/token logging, make replay/expiry cancellation safe, and return only operation identifiers via verified HTTPS Android App Links. Android re-reads authorized result from backend; never trust callback payload as successful import.
Before implementation, write exact proposed OpenAPI request/response/error contracts, schema/migration changes if needed, security threat cases, browser pages, required domain/assetlinks/OAuth configuration, and tests for account mismatch, interception, replay, forged return links and expiry. Mark every new route PROPOSED until implemented and tested. Keep these out of Appendix A's current contract. Wait for design confirmation before the implementation phase. After implementation update live OpenAPI and Android contract tests, then integrate the verified native flow. If backend/browser access is unavailable, report this dependency and leave the working browser/device-upload paths intact.
```

### Prompt 13 — Security, resource limits, and end-to-end tests

```text
Review all feature diffs for credential exposure, backup leaks, cross-account local files/jobs/cursors, stale callbacks, untrusted redirects/filenames, publicly readable PDFs, URI permission misuse, and oversized buffers/bitmaps. Confirm release HTTPS; allow emulator cleartext only in debug configuration. Check auth/refresh/error logs and crash reports redact credentials and sensitive file data. No storage keys or cloud secrets in APK. No arbitrary URL download or Google OAuth WebView.
Run behavioral unit/MockWebServer tests and instrumented/Compose tests supported by the real project. Reuse existing test tools; report exact commands/results/skips. Minimum end-to-end matrix: signup/login/refresh/logout; quota/duplicate author/title pair; SSE replay; upload and lost-response retry; range download and integrity check; resume page93; offline/process kill/reconnect; backward/final-page completion; multi-device conflict choice; document replacement; same-workspace different-user progress; Workspace A attempts Workspace B document/HEAD/range/progress/import status; Drive disabled/revoked/failed/browser success. Use only disposable test accounts/databases/files.
Profile a large permitted PDF on a low-memory emulator/device: bounded upload/download memory, Long byte counters, bitmap cap, cancellation and disk pressure. Test reader opened offline after restart and queued progress surviving until same-account login. Do not claim a cached app can revoke access instantly while offline; document that limitation. Do not claim background SSE is push.
Backend tests require dedicated PostgreSQL/JWT configuration; do not point them at production or rerun destructive setup on user data. If Android SDK/emulator/live Google deployment is unavailable, report exactly which verifications remain manual and provide reproducible steps.
```

### Prompt 14 — Release documentation and final acceptance

```text
Update Android README with SDK/build/run instructions, debug emulator and physical-device base URLs, HTTPS release configuration, signup/login/refresh, upload/replace semantics, private offline cache/removal, per-user resume, conflict choices, background sync limits, and browser versus native Drive status. Export/capture the deployed OpenAPI contract and record its backend revision; retain source-derived supplements for conflict/SSE semantics. Document every new dependency and migration in Android, plus token/backup/account-data policy.
List completed features versus externally blocked native OAuth/deployment items. Include actual changed files, tests run with results/skips, observed large-file behavior, known limitations, and deployment configuration owners. Run the repository's appropriate lint/unit/build/instrumented checks; do not fabricate task names or results. Compare every promised UI action with an existing/tested endpoint. No release claim while native Drive is represented as implemented but only a stub exists. Preserve existing catalogue/auth/signup/SSE behavior throughout.
```

## 5. Likely Android file changes (verify actual paths in Prompt 01)

Existing files likely modified: `data/network/BookApi.kt`, `RetrofitClient.kt`, `BookSseManager.kt`; `data/repository/BookRepository.kt`, `CursorStorage.kt`; `ui/viewmodel/BookViewModel.kt`, `BookUiState.kt`; `LoginDialog.kt`, `BookerBookCard.kt`, `BookDetailsDialog.kt`, `BookerLibraryScreen.kt`, `BookScreen.kt`, and actual navigation/manifest/build files as necessary. Keep `Book.kt` wire shape backward compatible.

Focused new concepts (names adapt to repository conventions): token/session models and coordinator; document/progress/Drive DTOs; document repository; private PDF cache; durable progress outbox; upload state/controller; reader screen/ViewModel/renderer adapter; conditional background sync worker; summary state; account-scoped tests. Do not introduce a new DI framework or local database merely to match suggested names.

## 6. Deployment and secrets

Android needs configured API origin and optional deployed Booker web origin. It must never contain DATABASE_PASSWORD, JWT_SECRET, GOOGLE_DRIVE_CLIENT_SECRET, or GOOGLE_DRIVE_ENCRYPTION_KEY. Google Picker's short-lived token/public restricted API key are browser-runtime configuration, not Android refresh credentials.

Backend operators configure `JWT_SECRET`, `JWT_ACCESS_TTL` (default PT15M), `JWT_REFRESH_TTL` (P7D), PostgreSQL, `BOOK_STORAGE_DIRECTORY`, `BOOK_MAX_FILE_SIZE`, `BOOK_MAX_REQUEST_SIZE`, `BOOK_MAX_PAGES`, `BOOK_WORKSPACE_STORAGE_LIMIT`, and HTTPS/proxy upload/SSE limits. For Drive: `GOOGLE_DRIVE_ENABLED`, `GOOGLE_DRIVE_CLIENT_ID`, `GOOGLE_DRIVE_CLIENT_SECRET`, `GOOGLE_DRIVE_REDIRECT_URI`, `GOOGLE_DRIVE_ENCRYPTION_KEY`, `GOOGLE_DRIVE_PICKER_API_KEY`, `GOOGLE_DRIVE_PROJECT_NUMBER`; configure a same-origin browser/callback deployment, consent and test users. Back up both database and PDF volume; all retained versions consume storage. S3, public links, native handoff and malware scanning are not current features.

## 7. Official implementation references

- Android document selection/URI permissions: [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files). Persistable permission can still be lost if a document is moved/deleted.
- Android rendering: [PdfRenderer](https://developer.android.com/reference/android/graphics/pdf/PdfRenderer). Check supported API levels and seekable descriptor requirements against the actual app.
- Google authorization restrictions: [OAuth 2.0 policies](https://developers.google.com/identity/protocols/oauth2/policies). Do not use an embedded user-agent for Google authorization.

## Appendix A — Source-derived OpenAPI client contract

The following JSON is valid OpenAPI format (JSON is also accepted by OpenAPI tooling). Save only the fenced JSON as `booker-android.openapi.json` if needed. It describes implemented client-facing routes; it deliberately excludes proposed native handoff APIs and the security-denied root HomeController. Reconcile with GET `/v3/api-docs` from the target deployment before client generation. Error responses marked as ApiError document application errors; clients must still tolerate security/proxy/framework bodies. Authorization is OR (Bearer or Basic), not both. The examples in Appendix B supplement important behavior that schema alone cannot express.

```json
{
  "openapi": "3.0.3",
  "info": {"title": "Booker Android source-derived client contract", "version": "2026-09-30", "description": "Hand-authored from inspected backend; reconcile with deployed /v3/api-docs. Not a server export. Nonblank string constraints also apply. Security/proxy errors can have different bodies."},
  "servers": [{"url": "http://10.0.2.2:8080", "description": "Android emulator debug only; replace with HTTPS API origin for release"}],
  "security": [{"bearerAuth": []}, {"basicAuth": []}],
  "paths": {
    "/api/auth/signup": {
      "post": {"operationId": "signup", "responses": {"201": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/SignupResponse"}}}}, "400": {"description": "Invalid input", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "409": {"description": "Conflict", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "security": [], "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/SignupRequest"}}}}}
    },
    "/api/auth/login": {
      "post": {"operationId": "login", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Tokens"}}}}, "400": {"description": "Invalid input", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "security": [], "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/LoginRequest"}}}}}
    },
    "/api/auth/refresh": {
      "post": {"operationId": "refresh", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Tokens"}}}}, "400": {"description": "Invalid input", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "security": [], "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/RefreshRequest"}}}}}
    },
    "/api/auth/logout": {
      "post": {"operationId": "logout", "responses": {"204": {"description": "Success"}, "400": {"description": "Invalid input", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "security": [], "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/RefreshRequest"}}}}}
    },
    "/api/workspace": {
      "get": {"operationId": "getWorkspace", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Workspace"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}}
    },
    "/api/books": {
      "get": {"operationId": "listBooks", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"type": "array", "items": {"$ref": "#/components/schemas/Book"}}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "parameters": [{"name": "author", "in": "query", "required": false, "schema": {"type": "string"}}, {"name": "title", "in": "query", "required": false, "schema": {"type": "string"}}], "description": "Exact filters; omit blank values. Unpaginated array."},
      "post": {"operationId": "createBook", "responses": {"201": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Book"}}}, "headers": {"Location": {"schema": {"type": "string"}}}}, "400": {"description": "Invalid input", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "403": {"description": "Access denied or quota exceeded", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "409": {"description": "Conflict", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/BookRequest"}}}}}
    },
    "/api/books/events": {
      "get": {"operationId": "bookEvents", "responses": {"200": {"description": "Success", "content": {"text/event-stream": {"schema": {"type": "string"}}}}, "400": {"description": "Invalid input", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "503": {"description": "Unavailable; retry with backoff", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "parameters": [{"name": "Last-Event-ID", "in": "header", "required": false, "schema": {"type": "string", "pattern": "^[0-9]+$"}}], "description": "ready events contain {}; book.created data follows BookEvent. Omit cursor for future only, 0 for replay. Heartbeat comments ~15 seconds; reconnect after five-minute close."}
    },
    "/api/books/{bookId}/document": {
      "post": {"operationId": "uploadDocument", "responses": {"201": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Document"}}}, "headers": {"Location": {"schema": {"type": "string"}}}}, "400": {"description": "Invalid input", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "403": {"description": "Access denied or quota exceeded", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "404": {"description": "Not found or inaccessible", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "413": {"description": "File/request too large", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "415": {"description": "Unsupported or unsafe PDF/media", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "503": {"description": "Unavailable; retry with backoff", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "parameters": [{"name": "bookId", "in": "path", "required": true, "schema": {"type": "integer", "format": "int64"}}, {"name": "Idempotency-Key", "in": "header", "required": true, "schema": {"type": "string", "format": "uuid"}}], "description": "Immutable replacement. Same account/book/key returns original operation result, without comparing retry bytes. Refetch active metadata.", "requestBody": {"required": true, "content": {"multipart/form-data": {"schema": {"type": "object", "required": ["file"], "properties": {"file": {"type": "string", "format": "binary"}}}, "encoding": {"file": {"contentType": "application/pdf"}}}}}},
      "get": {"operationId": "getDocument", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Document"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "404": {"description": "Not found or inaccessible", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "parameters": [{"name": "bookId", "in": "path", "required": true, "schema": {"type": "integer", "format": "int64"}}]}
    },
    "/api/books/{bookId}/document/content": {
      "get": {"operationId": "getDocumentContent", "responses": {"200": {"description": "Success", "content": {"application/pdf": {"schema": {"type": "string", "format": "binary"}}}, "headers": {"Content-Length": {"schema": {"type": "integer", "format": "int64"}}, "Accept-Ranges": {"schema": {"type": "string"}}, "ETag": {"schema": {"type": "string"}}, "Content-Disposition": {"schema": {"type": "string"}}, "Cache-Control": {"schema": {"type": "string"}}, "X-Content-Type-Options": {"schema": {"type": "string"}}}}, "400": {"description": "Invalid input", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "404": {"description": "Not found or inaccessible", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "409": {"description": "Conflict", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "416": {"description": "Unsatisfiable range; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "503": {"description": "Unavailable; retry with backoff", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "206": {"description": "Partial PDF content", "content": {"application/pdf": {"schema": {"type": "string", "format": "binary"}}}, "headers": {"Content-Length": {"schema": {"type": "integer", "format": "int64"}}, "Accept-Ranges": {"schema": {"type": "string"}}, "ETag": {"schema": {"type": "string"}}, "Content-Disposition": {"schema": {"type": "string"}}, "Cache-Control": {"schema": {"type": "string"}}, "X-Content-Type-Options": {"schema": {"type": "string"}}, "Content-Range": {"schema": {"type": "string"}}}}}, "parameters": [{"name": "bookId", "in": "path", "required": true, "schema": {"type": "integer", "format": "int64"}}, {"name": "documentId", "in": "query", "required": false, "schema": {"type": "string", "format": "uuid"}}, {"name": "download", "in": "query", "required": false, "schema": {"type": "boolean", "default": false}}, {"name": "Range", "in": "header", "required": false, "schema": {"type": "string", "example": "bytes=0-65535"}}], "description": "Pin active documentId. Authorized byte streaming. A replaced version returns 409. 416 body need not be ApiError."},
      "head": {"operationId": "headDocumentContent", "responses": {"200": {"description": "Success", "headers": {"Content-Length": {"schema": {"type": "integer", "format": "int64"}}, "Accept-Ranges": {"schema": {"type": "string"}}, "ETag": {"schema": {"type": "string"}}, "Content-Disposition": {"schema": {"type": "string"}}, "Cache-Control": {"schema": {"type": "string"}}, "X-Content-Type-Options": {"schema": {"type": "string"}}}}, "400": {"description": "Invalid input", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "404": {"description": "Not found or inaccessible", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "409": {"description": "Conflict", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "503": {"description": "Unavailable; retry with backoff", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "parameters": [{"name": "bookId", "in": "path", "required": true, "schema": {"type": "integer", "format": "int64"}}, {"name": "documentId", "in": "query", "required": false, "schema": {"type": "string", "format": "uuid"}}, {"name": "download", "in": "query", "required": false, "schema": {"type": "boolean", "default": false}}], "description": "No response body. Ignores Range and returns full file length."}
    },
    "/api/books/{bookId}/reading-progress": {
      "get": {"operationId": "getReadingProgress", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ReadingProgress"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "404": {"description": "Not found or inaccessible", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "parameters": [{"name": "bookId", "in": "path", "required": true, "schema": {"type": "integer", "format": "int64"}}]},
      "put": {"operationId": "updateReadingProgress", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ReadingProgress"}}}}, "400": {"description": "Invalid input", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "404": {"description": "Not found or inaccessible", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "409": {"description": "Revision conflict (ReadingProgress), or replaced document/reused operation (ApiError)", "content": {"application/json": {"schema": {"oneOf": [{"$ref": "#/components/schemas/ReadingProgress"}, {"$ref": "#/components/schemas/ApiError"}]}}}}}, "parameters": [{"name": "bookId", "in": "path", "required": true, "schema": {"type": "integer", "format": "int64"}}], "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ProgressUpdate"}}}}, "description": "Exact successful-operation replay returns current state. Stale revision can merge max page while preserving resume/time. Never silently overwrite on conflict."}
    },
    "/api/books/reading-summaries": {
      "get": {"operationId": "getReadingSummaries", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"type": "array", "items": {"$ref": "#/components/schemas/ReadingSummary"}}}}}, "400": {"description": "Invalid input", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "404": {"description": "Not found or inaccessible", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "parameters": [{"name": "bookIds", "in": "query", "required": true, "schema": {"type": "array", "items": {"type": "integer", "format": "int64"}, "minItems": 1, "maxItems": 100}, "style": "form", "explode": false}], "description": "Comma-separated IDs; any inaccessible book causes 404; no-document entries have null document and progress."}
    },
    "/api/integrations/google-drive/connect": {
      "post": {"operationId": "connectDrive", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/DriveConnect"}}}, "headers": {"Set-Cookie": {"schema": {"type": "string"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "503": {"description": "Unavailable; retry with backoff", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "description": "Sets HttpOnly booker_drive_binding cookie scoped to callback, SameSite=Lax, ten minutes, Secure on HTTPS. Must start and finish in same browser; not a native handoff."}
    },
    "/api/integrations/google-drive/callback": {
      "get": {"operationId": "driveCallback", "responses": {"200": {"description": "Success", "content": {"text/html": {"schema": {"type": "string"}}}, "headers": {"Set-Cookie": {"schema": {"type": "string"}}}}, "400": {"description": "Invalid input", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "403": {"description": "Access denied or quota exceeded", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "503": {"description": "Unavailable; retry with backoff", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "security": [], "parameters": [{"name": "state", "in": "query", "required": false, "schema": {"type": "string"}}, {"name": "code", "in": "query", "required": false, "schema": {"type": "string"}}, {"name": "booker_drive_binding", "in": "cookie", "required": false, "schema": {"type": "string"}}], "description": "Public route authenticated by single-use state and browser binding. Missing state/code/cookie fails with 400. Returns confirmation HTML, not redirect to Android."}
    },
    "/api/integrations/google-drive/connection": {
      "get": {"operationId": "getDriveConnection", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/DriveConnection"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}},
      "delete": {"operationId": "disconnectDrive", "responses": {"204": {"description": "Success"}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "description": "Deletes credentials locally; pending imports become FAILED; running jobs may finish; stored PDFs remain."}
    },
    "/api/integrations/google-drive/picker": {
      "get": {"operationId": "getDrivePicker", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/DrivePicker"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "403": {"description": "Access denied or quota exceeded", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "409": {"description": "Conflict", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "503": {"description": "Unavailable; retry with backoff", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "description": "Web Google Picker configuration, not a native file list. Keep token in memory."}
    },
    "/api/books/{bookId}/document/imports/google-drive": {
      "post": {"operationId": "importDrivePdf", "responses": {"202": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ImportStatus"}}}, "headers": {"Location": {"schema": {"type": "string"}}}}, "400": {"description": "Invalid input", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "404": {"description": "Not found or inaccessible", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "409": {"description": "Conflict", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "parameters": [{"name": "bookId", "in": "path", "required": true, "schema": {"type": "integer", "format": "int64"}}, {"name": "Idempotency-Key", "in": "header", "required": true, "schema": {"type": "string", "format": "uuid"}}], "requestBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/DriveImportRequest"}}}}, "description": "Only selected Drive file IDs. Same operation/file replay returns existing status. File URLs rejected."}
    },
    "/api/books/{bookId}/document/imports/{importId}": {
      "get": {"operationId": "getDriveImport", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ImportStatus"}}}}, "400": {"description": "Invalid input", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "404": {"description": "Not found or inaccessible", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}}, "parameters": [{"name": "bookId", "in": "path", "required": true, "schema": {"type": "integer", "format": "int64"}}, {"name": "importId", "in": "path", "required": true, "schema": {"type": "string", "format": "uuid"}}], "description": "Only initiating account can read status, even within a shared workspace."}
    },
    "/actuator/health": {
      "get": {"operationId": "health", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Health"}}}}}, "security": []}
    },
    "/v3/api-docs": {
      "get": {"operationId": "openApi", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"type": "object", "additionalProperties": true}}}}}, "security": []}
    },
    "/api/books/{bookId}": {
      "get": {"operationId": "getBookById", "responses": {"200": {"description": "Success", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Book"}}}}, "401": {"description": "Authentication required or rejected; body may differ", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "404": {"description": "Not found or inaccessible", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ApiError"}}}}, "400": {"description": "Invalid numeric book ID"}}, "parameters": [{"name": "bookId", "in": "path", "required": true, "schema": {"type": "integer", "format": "int64"}}]}
    }
  },
  "components": {
    "securitySchemes": {
      "bearerAuth": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"},
      "basicAuth": {"type": "http", "scheme": "basic"}
    },
    "schemas": {
      "SignupRequest": {"type": "object", "properties": {"workspaceName": {"type": "string", "minLength": 1, "maxLength": 100}, "email": {"type": "string", "format": "email", "maxLength": 254}, "password": {"type": "string", "format": "password", "minLength": 12, "maxLength": 64}}, "required": ["workspaceName", "email", "password"]},
      "SignupResponse": {"type": "object", "properties": {"workspaceId": {"type": "string", "format": "uuid"}, "workspaceName": {"type": "string"}, "email": {"type": "string"}, "plan": {"type": "string", "enum": ["FREE"]}}, "required": ["workspaceId", "workspaceName", "email", "plan"]},
      "LoginRequest": {"type": "object", "properties": {"email": {"type": "string", "format": "email", "maxLength": 254}, "password": {"type": "string", "format": "password", "minLength": 1, "maxLength": 64}}, "required": ["email", "password"]},
      "RefreshRequest": {"type": "object", "properties": {"refreshToken": {"type": "string", "minLength": 1, "maxLength": 4096}}, "required": ["refreshToken"]},
      "Tokens": {"type": "object", "properties": {"accessToken": {"type": "string"}, "refreshToken": {"type": "string"}, "tokenType": {"type": "string", "enum": ["Bearer"]}, "expiresIn": {"type": "integer", "format": "int64"}, "refreshExpiresIn": {"type": "integer", "format": "int64"}}, "required": ["accessToken", "refreshToken", "tokenType", "expiresIn", "refreshExpiresIn"]},
      "Workspace": {"type": "object", "properties": {"id": {"type": "string", "format": "uuid"}, "name": {"type": "string"}, "plan": {"type": "string", "enum": ["FREE", "PRO"]}, "book_limit": {"type": "integer"}, "books_used": {"type": "integer", "format": "int64"}}, "required": ["id", "name", "plan", "book_limit", "books_used"]},
      "BookRequest": {"type": "object", "properties": {"title": {"type": "string", "minLength": 1, "maxLength": 255}, "author": {"type": "string", "minLength": 1, "maxLength": 255}, "publishedDate": {"type": "string", "minLength": 1, "maxLength": 20}, "description": {"type": "string", "maxLength": 5000, "nullable": true}, "completed": {"type": "boolean"}}, "required": ["title", "author", "publishedDate", "completed"]},
      "Book": {"type": "object", "properties": {"id": {"type": "integer", "format": "int64"}, "title": {"type": "string", "minLength": 1, "maxLength": 255}, "author": {"type": "string", "minLength": 1, "maxLength": 255}, "publishedDate": {"type": "string", "minLength": 1, "maxLength": 20}, "description": {"type": "string", "maxLength": 5000, "nullable": true}, "completed": {"type": "boolean"}}, "required": ["id", "title", "author", "publishedDate", "description", "completed"]},
      "Document": {"type": "object", "properties": {"bookId": {"type": "integer", "format": "int64"}, "documentId": {"type": "string", "format": "uuid"}, "fileName": {"type": "string"}, "fileSize": {"type": "integer", "format": "int64"}, "mimeType": {"type": "string", "enum": ["application/pdf"]}, "pageCount": {"type": "integer", "minimum": 1}, "checksum": {"type": "string", "description": "SHA-256 lowercase hex", "pattern": "^[0-9a-f]{64}$"}, "sourceType": {"type": "string", "enum": ["UPLOAD", "GOOGLE_DRIVE"]}, "active": {"type": "boolean"}, "createdAt": {"type": "string", "format": "date-time"}}, "required": ["bookId", "documentId", "fileName", "fileSize", "mimeType", "pageCount", "checksum", "sourceType", "active", "createdAt"]},
      "ReadingProgress": {"type": "object", "properties": {"bookId": {"type": "integer", "format": "int64"}, "documentId": {"type": "string", "format": "uuid"}, "currentPage": {"type": "integer", "minimum": 0}, "totalPages": {"type": "integer", "minimum": 1}, "pagesRead": {"type": "integer", "minimum": 0, "description": "Maximum page reached, not distinct viewed-page count"}, "progressPercentage": {"type": "number", "minimum": 0, "maximum": 100}, "lastReadAt": {"type": "string", "format": "date-time", "nullable": true}, "completed": {"type": "boolean"}, "resumePage": {"type": "integer", "minimum": 1}, "version": {"type": "integer", "format": "int64", "minimum": 0}}, "required": ["bookId", "documentId", "currentPage", "totalPages", "pagesRead", "progressPercentage", "lastReadAt", "completed", "resumePage", "version"]},
      "ProgressUpdate": {"type": "object", "properties": {"documentId": {"type": "string", "format": "uuid"}, "currentPage": {"type": "integer", "minimum": 1, "description": "At most active document pageCount"}, "version": {"type": "integer", "format": "int64", "minimum": 0, "description": "Required and non-null; explicitly send 0 on first save"}, "operationId": {"type": "string", "format": "uuid"}}, "required": ["documentId", "currentPage", "version", "operationId"]},
      "ReadingSummary": {"type": "object", "properties": {"bookId": {"type": "integer", "format": "int64"}, "document": {"type": "object", "properties": {"bookId": {"type": "integer", "format": "int64"}, "documentId": {"type": "string", "format": "uuid"}, "fileName": {"type": "string"}, "fileSize": {"type": "integer", "format": "int64"}, "mimeType": {"type": "string", "enum": ["application/pdf"]}, "pageCount": {"type": "integer", "minimum": 1}, "checksum": {"type": "string", "description": "SHA-256 lowercase hex", "pattern": "^[0-9a-f]{64}$"}, "sourceType": {"type": "string", "enum": ["UPLOAD", "GOOGLE_DRIVE"]}, "active": {"type": "boolean"}, "createdAt": {"type": "string", "format": "date-time"}}, "required": ["bookId", "documentId", "fileName", "fileSize", "mimeType", "pageCount", "checksum", "sourceType", "active", "createdAt"], "nullable": true}, "progress": {"type": "object", "properties": {"bookId": {"type": "integer", "format": "int64"}, "documentId": {"type": "string", "format": "uuid"}, "currentPage": {"type": "integer", "minimum": 0}, "totalPages": {"type": "integer", "minimum": 1}, "pagesRead": {"type": "integer", "minimum": 0, "description": "Maximum page reached, not distinct viewed-page count"}, "progressPercentage": {"type": "number", "minimum": 0, "maximum": 100}, "lastReadAt": {"type": "string", "format": "date-time", "nullable": true}, "completed": {"type": "boolean"}, "resumePage": {"type": "integer", "minimum": 1}, "version": {"type": "integer", "format": "int64", "minimum": 0}}, "required": ["bookId", "documentId", "currentPage", "totalPages", "pagesRead", "progressPercentage", "lastReadAt", "completed", "resumePage", "version"], "nullable": true}}, "required": ["bookId", "document", "progress"]},
      "ApiError": {"type": "object", "properties": {"dateTime": {"type": "string", "description": "LocalDateTime; no guaranteed timezone offset"}, "status": {"type": "integer"}, "error": {"type": "string"}, "message": {"type": "string", "nullable": true}, "path": {"type": "string"}}, "required": ["dateTime", "status", "error", "message", "path"]},
      "DriveConnect": {"type": "object", "properties": {"authorizationUrl": {"type": "string", "format": "uri"}}, "required": ["authorizationUrl"]},
      "DriveConnection": {"type": "object", "properties": {"connected": {"type": "boolean"}}, "required": ["connected"]},
      "DrivePicker": {"type": "object", "properties": {"accessToken": {"type": "string", "description": "Short-lived Google token, memory only"}, "apiKey": {"type": "string"}, "appId": {"type": "string"}}, "required": ["accessToken", "apiKey", "appId"]},
      "DriveImportRequest": {"type": "object", "properties": {"fileId": {"type": "string", "pattern": "^[A-Za-z0-9_-]{1,200}$"}}, "required": ["fileId"]},
      "ImportStatus": {"type": "object", "properties": {"importId": {"type": "string", "format": "uuid"}, "bookId": {"type": "integer", "format": "int64"}, "fileId": {"type": "string"}, "status": {"type": "string", "enum": ["PENDING", "RUNNING", "COMPLETED", "FAILED"]}, "documentId": {"type": "string", "format": "uuid", "nullable": true}, "message": {"type": "string", "nullable": true}}, "required": ["importId", "bookId", "fileId", "status", "documentId", "message"]},
      "BookEvent": {"type": "object", "properties": {"eventId": {"type": "string"}, "type": {"type": "string", "enum": ["book.created"]}, "schemaVersion": {"type": "integer", "enum": [2]}, "occurredAt": {"type": "string", "format": "date-time"}, "book": {"$ref": "#/components/schemas/Book"}}, "required": ["eventId", "type", "schemaVersion", "occurredAt", "book"]},
      "Health": {"type": "object", "properties": {"status": {"type": "string"}}, "required": ["status"]}
    }
  }
}
```

## Appendix B — Wire examples and acceptance fixtures

These UUIDs and IDs are illustrative; use values returned by your own test account. They are not seeded resources. Token values are placeholders. HTTP paths here include `/api`; Retrofit annotations with an `/api/` base omit that prefix.

### Signup, login, workspace, catalogue

```http
POST /api/auth/signup
Content-Type: application/json

{"workspaceName":"My Library","email":"reader@example.com","password":"replace-this-password"}
```

201 response:

```json
{"workspaceId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","workspaceName":"My Library","email":"reader@example.com","plan":"FREE"}
```

```http
POST /api/auth/login
Content-Type: application/json

{"email":"reader@example.com","password":"replace-this-password"}
```

200 response (TTL values depend on configuration):

```json
{"accessToken":"<access-jwt>","refreshToken":"<refresh-jwt>","tokenType":"Bearer","expiresIn":900,"refreshExpiresIn":604800}
```

`POST /api/auth/refresh` and `POST /api/auth/logout` both accept `{"refreshToken":"<latest-refresh-jwt>"}` with no Authorization needed. Refresh returns a replacement Tokens object; logout returns 204 with no JSON. GET `/api/workspace` with Bearer:

```json
{"id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","name":"My Library","plan":"FREE","book_limit":100,"books_used":1}
```

POST `/api/books`:

```json
{"title":"Effective Java","author":"Joshua Bloch","publishedDate":"2018","description":null,"completed":false}
```

201 response adds numeric `"id":1` to these fields. GET `/api/books` returns an array of those objects, even with zero or one result. GET `/api/books/1` returns one object. Exact filters use `/api/books?author=Joshua%20Bloch&title=Effective%20Java`.

### Multipart upload

```http
POST /api/books/1/document
Authorization: Bearer <access-jwt>
Idempotency-Key: 22222222-2222-4222-8222-222222222222
Content-Type: multipart/form-data; boundary=example-boundary

--example-boundary
Content-Disposition: form-data; name="file"; filename="book.pdf"
Content-Type: application/pdf

<PDF binary bytes>
--example-boundary--
```

Let Retrofit/OkHttp generate the real boundary; never hardcode it. 201 returns a document such as this (checksum below is a placeholder and must be replaced by the actual hash in integrity tests):

```json
{"bookId":1,"documentId":"11111111-1111-4111-8111-111111111111","fileName":"book.pdf","fileSize":12345678,"mimeType":"application/pdf","pageCount":144,"checksum":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","sourceType":"UPLOAD","active":true,"createdAt":"2026-09-29T10:00:00Z"}
```

`GET /api/books/1/document` returns the same shape for the currently active PDF. GET `/api/books/1/document/content?documentId=11111111-1111-4111-8111-111111111111` returns PDF bytes. With `Range: bytes=0-4`, a valid file can return 206, `Content-Range: bytes 0-4/12345678` and the first five bytes `%PDF-`. Never attempt to decode content as Document JSON.

### First-open and save

GET `/api/books/1/reading-progress` before reading:

```json
{"bookId":1,"documentId":"11111111-1111-4111-8111-111111111111","currentPage":0,"totalPages":144,"pagesRead":0,"progressPercentage":0.00,"lastReadAt":null,"completed":false,"resumePage":1,"version":0}
```

PUT `/api/books/1/reading-progress`:

```json
{"documentId":"11111111-1111-4111-8111-111111111111","currentPage":93,"version":0,"operationId":"33333333-3333-4333-8333-333333333333"}
```

200:

```json
{"bookId":1,"documentId":"11111111-1111-4111-8111-111111111111","currentPage":93,"totalPages":144,"pagesRead":93,"progressPercentage":64.58,"lastReadAt":"2026-09-29T10:10:00Z","completed":false,"resumePage":93,"version":1}
```

Next save navigating backward uses page20, version1, and a **new** operationId. Response has currentPage=20, resumePage=20, pagesRead=93, percentage=64.58, version=2. Neither operation changes Book.completed.

409 revision-conflict response is a complete ReadingProgress object, for example:

```json
{"bookId":1,"documentId":"11111111-1111-4111-8111-111111111111","currentPage":85,"totalPages":144,"pagesRead":93,"progressPercentage":64.58,"lastReadAt":"2026-09-29T10:12:00Z","completed":false,"resumePage":85,"version":3}
```

409 replacement response is instead ApiError:

```json
{"dateTime":"2026-09-29T16:13:00","status":409,"error":"Conflict","message":"The PDF was replaced; reopen the book","path":"/api/books/1/reading-progress"}
```

A sample no-PDF batch entry from GET `/api/books/reading-summaries?bookIds=2`:

```json
[{"bookId":2,"document":null,"progress":null}]
```

### SSE

```text
id: 17
event: ready
retry: 3000
data: {}

id: 18
event: book.created
data: {"eventId":"18","type":"book.created","schemaVersion":2,"occurredAt":"2026-09-29T10:00:00.000Z","book":{"id":1,"title":"Effective Java","author":"Joshua Bloch","publishedDate":"2018","description":null,"completed":false}}

```

### Google Drive

Connection status is `{"connected":false}` or true; false does not distinguish disabled from unconnected. Connect returns `{"authorizationUrl":"https://accounts.google.com/o/oauth2/v2/auth?..."}` **and** the browser-binding Set-Cookie header. Do not use this example as a native OAuth flow. Picker returns `{"accessToken":"<short-lived-google-token>","apiKey":"<public-restricted-key>","appId":"<google-project-number>"}` for web Picker. No file list is returned.

```http
POST /api/books/1/document/imports/google-drive
Authorization: Bearer <access-jwt>
Idempotency-Key: 44444444-4444-4444-8444-444444444444
Content-Type: application/json

{"fileId":"selected_drive_file_id"}
```

202, with Location `/api/books/1/document/imports/44444444-4444-4444-8444-444444444444`:

```json
{"importId":"44444444-4444-4444-8444-444444444444","bookId":1,"fileId":"selected_drive_file_id","status":"PENDING","documentId":null,"message":null}
```

Poll that authorized path. Success has status COMPLETED and documentId; failure has status FAILED and a safe message. A successful poll HTTP 200 does **not** mean the import succeeded. A completed historical import does not guarantee its document is still active: refetch active metadata. There is no CANCELLED status or cancel-job endpoint.

## Appendix C — Completion checklist for the implementing AI

- [ ] Actual Android checkout inspected; all source assumptions corrected; Phase 1 confirmed.
- [ ] DTOs and Retrofit URLs match current deployed backend; source/live OpenAPI differences recorded.
- [ ] Session rotation, error fallbacks, logout and account switching tested.
- [ ] Existing signup/catalogue/ID lookup/quota/SSE/notifications still work.
- [ ] Device upload streams, reports progress and preserves retry identity/bytes.
- [ ] Private download verifies bytes, safely resumes or restarts, and isolates document versions/accounts.
- [ ] Reader restores page93, survives rotation/process recreation, and opens cached PDF offline.
- [ ] Durable sync preserves maximum and resume, exact uncertain operations, newer local intent and explicit conflict choice.
- [ ] Personal completion stays independent of manual Book.completed.
- [ ] Summaries batch correctly and distinguish errors from no document.
- [ ] Drive browser/device-provider paths are labeled accurately; any seamless native handoff has a separately implemented/tested backend contract.
- [ ] Cross-workspace/account, malicious/error, large-file and lifecycle cases covered.
- [ ] Build/test commands and actual results recorded; untested Google/device/deployment items disclosed.
- [ ] No unsupported edit/delete/pagination/push/password-reset/billing actions presented as working.
