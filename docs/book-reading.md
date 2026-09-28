# PDF documents and personal reading progress

## Recovery checkpoint — 2026-09-28

Git HEAD `b4e7f8f` contains the workspace/authentication baseline. The recovered
working tree already includes the document model and the subsequent backend and
Angular implementation described below; those changes are not yet committed.
Phase 2's data-model decision is recorded explicitly in the schema section.

Recovery verification completed:

- All 29 backend tests passed against a newly initialized, isolated PostgreSQL 18
  instance, including all Flyway migrations V1–V7 and the event persistence test.
- Three additional document-model regression tests verify concurrent upload retry
  deduplication, concurrent replacements retaining exactly one active version,
  and rollback to the previous active version when metadata insertion fails.
- All 13 Angular tests passed on a copy of the current `../booker-ui` working tree.
  Its production build passed, with an existing `app.css` size-budget warning.

This is not completion of every phase in `AGENTS.md`: native Android upload,
reader and offline synchronization still require the Android client source. The
nearby `../android-app` directory is empty. Live Google OAuth/Picker verification
also remains pending deployment credentials. Storage retention and production
deployment limitations are documented below. No live deployment was performed.

## Architecture and compatibility

The existing Spring MVC/JPA/JDBC architecture is retained. Books belong to a
workspace and keep their existing metadata, creation API, `BookResponse` and
`book.created` notification. `Book.completed` remains the existing metadata flag.
It is **not** overwritten by personal PDF completion.

New packages:

- `document`: workspace book access, document metadata repository, PDF validation,
  upload service and streaming controller.
- `storage`: `FileStorageService` and private local filesystem implementation.
- `reading`: per-account progress, concurrency and batch summaries.
- `drive`: Google OAuth, credential encryption, API gateway and durable import queue.

Both existing HTTP Basic and JWT bearer authentication work on the new APIs.
Workspace identity and account email always come from `WorkspacePrincipal`.
Books/files are workspace-shared; progress and Drive credentials are per account.
Another workspace's book/document returns 404. Google import status also requires
that the requesting account created the import.

## Schema and versioning

### Phase 2 decision

`Book` remains the existing JPA entity with a generated BIGINT ID and workspace
ownership. Documents use the existing JDBC/SQL style already used for workspace
accounts and notifications; a second JPA relationship on `Book` is unnecessary.
Document authorization derives the workspace through its book, rather than storing
a second workspace ID that could disagree with `books.workspace_id`.

A book may have zero or many immutable document versions, with **at most one
active version**. Zero preserves existing books without PDFs. Retaining replaced
versions avoids destroying the last valid PDF and gives future version-history
features a stable document UUID. The public API currently exposes only the active
version. The partial unique index enforces this rule in PostgreSQL, and a book row
lock serializes activation and reading-position updates. Activation deactivates the
previous row and inserts the new row in the same transaction, so an insertion
failure restores the previous active version.

The storage provider/key pair identifies bytes outside PostgreSQL. `LOCAL` is the
only implemented provider; the provider field and `FileStorageService` boundary
allow a future object-storage implementation without changing book metadata or
client document IDs. Size, checksum and page count belong to each immutable
version. `updated_at` tracks deactivation; the bytes and version metadata are not
edited in place. Retention consumes storage and needs an explicit future cleanup
policy rather than silent deletion on replacement.

Flyway V6 adds:

| Table | Important fields and constraints |
| --- | --- |
| `book_documents` | UUID id; book FK; provider and unique generated storage key; sanitized filename; MIME; byte size; parsed page count; SHA-256; UPLOAD/GOOGLE_DRIVE source; creator FK; operation UUID; active; timestamps |
| `reading_progress` | Primary key `(user_email, book_id)`; composite document/book FK; current page; maximum page reached; revision; last-read/created/updated timestamps |
| `reading_progress_operations` | Primary key `(user_email, operation_id)`; book/document FK; original page/revision; timestamp |

A partial unique index enforces one active document per book. Uploads create new,
immutable versions. A replacement is activated transactionally only after its
bytes have been stored and validated. Existing active files remain available if
validation or the metadata transaction fails. Same-key retries return the original
upload result; do not reuse an upload key for a different file.

V7 adds encrypted Drive connections, expiring browser-bound OAuth states, and a
persistent import queue. Imports have PENDING/RUNNING/COMPLETED/FAILED states.

### Phase 4 migration design

V6 remains the migration that creates the document, personal progress and retry
operation tables. It provides document/book/user foreign keys, the account/book
primary key, the one-active-document partial unique index, upload retry uniqueness,
and indexes for book versions, book progress and a user's recent reading.

V8 (`V8__book_reading_reference_indexes.sql`) completes lookup coverage:

| Index | Purpose |
| --- | --- |
| `book_documents(created_by)` | Locate document references when checking an account deletion |
| `reading_progress(document_id, book_id)` | Locate progress when deleting a document version |
| `reading_progress_operations(book_id, document_id)` | Locate retry records when deleting a book or document version |

PostgreSQL does not automatically index referencing foreign-key columns. These
indexes avoid scanning all reading data for those referential-integrity checks.
V8 adds indexes only; it does not rewrite books, PDFs, progress or operation records.
Previously executed migration files are preserved so Flyway checksum validation
continues to work. As with existing migrations, normal index creation is
transactional; it can block writes while building on a large populated table.
Schedule that upgrade during a suitable maintenance window.

`BookReadingMigrationTest` runs Flyway in temporary schemas and removes them after
each test. It covers a fresh install, a V5-to-latest upgrade preserving every book
and book event, and a populated V7-to-latest upgrade preserving document/progress/
retry metadata. It also checks foreign keys, active-document and user/book
uniqueness, page constraints, document-delete cascades, validation and a second
migration run with no pending changes. It uses the same dedicated test database
configuration as the other backend integration tests; no extra library is needed.

Phase 4 verification: all 36 backend tests passed on isolated PostgreSQL 18,
including the three Flyway migration tests. No tests were skipped.

No existing book data is rewritten or backfilled. Old books simply have no PDF.
The project has no Flyway undo convention. Back up the database and file volume
before deployment; use a forward migration or restore both backups for rollback.
Do not remove migration history entries on a running installation.

## Progress semantics and synchronization

### Phase 3 data model

Progress belongs to an authenticated account and a book, using the existing
`workspace_users.email` identity rather than introducing a separate user ID.
The existing V6 migration already supplies the required constraints; Phase 3
does not require another table or a change to `Book`.

| Stored field | Responsibility |
| --- | --- |
| `user_email`, `book_id` | Composite primary key: exactly one row per account/book; both have foreign keys |
| `document_id` | Pins progress to a PDF version; composite foreign key ensures that document belongs to this book |
| `current_page` | Resume position, starting at 1 for persisted progress |
| `max_page_reached` | Greatest page reached for this document; never below current page |
| `version` | Server revision for detecting concurrent updates |
| `last_read_at` | Server timestamp of the last accepted resume-position update |
| `created_at`, `updated_at` | Creation and modification timestamps of the account/book row |

`totalPages` is derived from `book_documents.page_count`. Percentage and personal
completion are calculated by `ReadingProgress.of` from the maximum page and total;
they are not independently stored values that could drift. Manual
`Book.completed` retains its existing meaning. Replacing a document resets the
visible progress; the next accepted save reuses the same account/book row for the
new document, preserving the row's creation timestamp.

`ReadingProgressService` obtains the account and workspace only from
`WorkspacePrincipal`. Reads authorize the book before loading personal progress;
writes lock the authorized book inside a transaction before checking its document
and revision. Neither a user ID nor workspace ID is part of the update model.
The primary key and transactional upsert prevent duplicate progress rows, including
concurrent first saves. `reading_progress_operations` records accepted operation
UUIDs per account so retries survive process restarts without updating timestamps.
Conflict responses do not consume operation UUIDs; resolving a conflict uses a
fresh operation UUID and the returned revision.

Phase 3 verification: all 33 backend tests passed on isolated PostgreSQL, with
four added integration tests covering persisted resume state and retry timestamps,
per-user uniqueness and authorization, accepted-operation reuse, and future versus
stale revisions. Existing tests also cover concurrent saves and PDF replacement.

Before first reading, GET returns `currentPage: 0`, `resumePage: 1`, `pagesRead: 0`,
`lastReadAt: null`, `version: 0`. GET does not create a progress row. PUT accepts
only pages 1 through the active document's page count.

- `currentPage`: last successfully saved resume position; backward navigation is allowed.
- `pagesRead`: maximum page reached. This is not proof that every preceding page was read.
- `totalPages`: parsed from the PDF; never supplied by the client.
- `progressPercentage`: `pagesRead * 100 / totalPages`, rounded half-up to two decimals.
- `completed`: maximum page reached equals total pages. Navigating backward preserves completion.
- `lastReadAt`: server timestamp of an accepted position update, not the client's clock.

Example: page 93 of 144 yields 64.58%. Reopening returns resumePage 93.

Every PUT carries the last server revision and a fresh operation UUID. Retries of
that same request reuse the same UUID and body. They do not change timestamps or
create another row. Reusing an accepted operation UUID with a different body returns 409.
A duplicate retry returns the latest progress, which may include later updates.

When two devices update the same revision, one succeeds. The stale request merges
the maximum page reached, preserves the accepted resume position, and receives
409 with current progress. If merging changes the maximum, it increments the
revision but does not change lastReadAt. The client asks which resume position to
keep. A revision newer than the server's returns 409 with current progress and
does not merge pages or change persisted state. Choosing its local position sends
a new operation against the returned revision. This avoids trusting clock skew or silently discarding intentional
backward navigation. Document replacement invalidates old progress updates; reopen
and load the new document. Progress for the new document starts at zero, even if
an older progress row remains until the next accepted update.

## API conventions

Except for the Google callback, every endpoint below requires an authenticated
account. Use `Authorization: Bearer <accessToken>` (or existing Basic credentials).
No workspace ID or user ID is accepted from clients.

Normal application errors use the existing `ApiError` fields:
`dateTime`, `status`, `error`, `message`, `path`. Authentication errors and servlet
range responses may have no JSON body. A stale progress revision is the explicit
exception: its 409 body is a `ReadingProgress` object.

Common errors: 400 invalid/missing fields; 401 missing/invalid authentication;
404 missing or unauthorized book/document. File APIs also return 403 workspace
storage budget exhausted; 413 file/request too large; 415 unsupported/invalid PDF;
503 validator busy, unavailable storage or transient Google failures.

### Upload

Phase 6 retains immutable document versions: each new successful upload becomes
the active PDF while earlier files remain stored. Validation and storage must
succeed before the transactional activation; a failed replacement keeps the old
PDF active. Book metadata and its manual completion flag remain unchanged.

`POST /api/books/{bookId}/document`

Multipart field `file`; required UUID `Idempotency-Key` header. Returns 201 with
Location and document metadata. Existing books must be created through the normal
book API first. The upload endpoint does not alter metadata or emit a book-created event.

```sh
curl -X POST "$API/api/books/1/document" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Idempotency-Key: $UPLOAD_UUID" \
  -F 'file=@book.pdf;type=application/pdf'
```

```json
{
  "bookId": 1,
  "documentId": "11111111-1111-4111-8111-111111111111",
  "fileName": "book.pdf",
  "fileSize": 12345678,
  "mimeType": "application/pdf",
  "pageCount": 144,
  "checksum": "sha256-hex-value",
  "sourceType": "UPLOAD",
  "active": true,
  "createdAt": "2026-09-28T10:00:00Z"
}
```

The same operation may return `active:false` if a later upload replaced its
original result. Retrieve current metadata to obtain the active document.

The endpoint checks book access using the authenticated workspace, requires a
sanitized `.pdf` filename and `application/pdf` MIME type, enforces the streaming
byte limit, and parses the PDF to determine page count. A successful response
contains metadata only, with `Location` and `Cache-Control: no-store` headers.
Missing file/header or invalid UUID returns 400; unauthenticated upload returns
401; a missing/inaccessible book returns 404. Empty files return 400, oversized
files 413, and invalid extension/MIME/content 415. Storage I/O failures during
upload or inspection return a safe 503 without filesystem details. If cleanup
also fails, it is logged for maintenance without replacing the original response;
the unreferenced file requires the reconciliation described under storage.

Phase 6 verification: all 48 backend tests passed, with none skipped. Three new
MockMvc tests cover upload request validation, authentication/missing books and
replacement metadata; three service tests cover storage failures and cleanup
error preservation. MockMvc oversized-file coverage exercises the service byte
limit; it does not simulate the servlet container's multipart parsing limit.

### Metadata

`GET /api/books/{bookId}/document`

Returns 200 with the metadata above, or 404 when no active PDF exists.

```sh
curl "$API/api/books/1/document" -H "Authorization: Bearer $ACCESS_TOKEN"
```

### Streaming

`GET /api/books/{bookId}/document/content`

Optional `documentId` pins the active version; a different current version returns
409. Optional `download=true` selects attachment rather than inline disposition.
GET returns application/pdf, safe Content-Disposition and a document-version ETag.
HEAD returns headers only. A valid byte Range returns 206 and Content-Range;
an unsatisfiable range returns 416. Authorization is checked before serving ranges.
Responses use Cache-Control: no-store. Clients may implement explicit protected
app-private offline storage; there are no public storage URLs.

Phase 7 uses Spring's resource response handling to stream GET bodies and byte
ranges without loading the full PDF into an application byte array. Prefix,
suffix and open-ended ranges are supported. An explicit HEAD handler authorizes
the book, checks the pinned version and storage availability, and returns the
stored complete file length without opening a PDF stream. Range on HEAD is ignored.
Both methods return `Accept-Ranges: bytes`, a version ETag, safe inline/attachment
Content-Disposition and `X-Content-Type-Options: nosniff`.

A book without a document returns 404. Missing stored bytes return 503 with a safe
error message. Cross-workspace requests return 404 before document-version or range
evaluation, and unauthenticated requests return 401. Resource reads use the storage
abstraction; no local filesystem path appears in the endpoint or metadata response.
With immutable stored bytes, HEAD length comes from trusted upload metadata.
Out-of-band edits to stored files are unsupported. If storage fails after streaming
headers are sent, the response can be interrupted rather than replaced with JSON;
the reader must retry. Direct signed URLs remain a future object-storage option,
with the expiry/access tradeoff described under storage and deployment.

Phase 7 verification: 52 tests passed in the full backend run. The additional
5 GB HEAD test initially exposed a 32-bit Content-Length limitation in Spring's
mock response; it passed after checking the controller's long-valued header
directly. All 53 tests therefore passed across the full and focused runs, with
none skipped. Four added integration tests exercise suffix/open-ended ranges,
HEAD/download headers, authorization/version checks and missing storage; the
focused test verifies that a simulated large resource is never opened by HEAD.

```sh
curl "$API/api/books/1/document/content?documentId=$DOCUMENT_UUID" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Range: bytes=0-65535'
```

### Get progress

`GET /api/books/{bookId}/reading-progress`

```sh
curl "$API/api/books/1/reading-progress" -H "Authorization: Bearer $ACCESS_TOKEN"
```

```json
{
  "bookId": 1,
  "documentId": "11111111-1111-4111-8111-111111111111",
  "currentPage": 93,
  "totalPages": 144,
  "pagesRead": 93,
  "progressPercentage": 64.58,
  "lastReadAt": "2026-09-28T10:00:00Z",
  "completed": false,
  "resumePage": 93,
  "version": 1
}
```

### Save progress

`PUT /api/books/{bookId}/reading-progress`

```sh
curl -X PUT "$API/api/books/1/reading-progress" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Content-Type: application/json' \
  -d '{"documentId":"11111111-1111-4111-8111-111111111111","currentPage":93,"version":0,"operationId":"22222222-2222-4222-8222-222222222222"}'
```

Returns 200 with progress; 400 for an out-of-bounds page; 409 for stale revision,
replaced document, or operation-ID reuse. Do not compute persisted percentages
on the client. See synchronization rules above before automatically retrying a 409.

### Book list summaries

`GET /api/books/reading-summaries?bookIds=1,2,3`

Returns 200 with `[{bookId, document, progress}]`. A book without a PDF has null
document and progress. Accepts 1–100 IDs; uses one joined query. Any inaccessible
ID returns 404 for the request. Existing book list responses remain unchanged.

```sh
curl "$API/api/books/reading-summaries?bookIds=1,2" -H "Authorization: Bearer $ACCESS_TOKEN"
```

## Google Drive APIs and setup

Enable Drive API and Google Picker in one Google Cloud project. Configure a web
OAuth client and its exact callback URI. Request only `drive.file`; the Picker
lets users grant access to selected files. Google OAuth consent/test-user setup
must match the deployment. No arbitrary URL is accepted or fetched.

| Method and path | Request | Response / additional errors |
| --- | --- | --- |
| POST `/api/integrations/google-drive/connect` | Empty body, authenticated | 200 `{authorizationUrl}` plus HttpOnly, SameSite=Lax browser-binding cookie; 503 if disabled |
| GET `/api/integrations/google-drive/callback` | Google `state`, `code`, and binding cookie | 200 confirmation HTML; 400 invalid/expired state or denied consent; 403 denied Google grant; 503 upstream unavailable |
| GET `/api/integrations/google-drive/connection` | Authenticated | 200 `{connected: boolean}` |
| DELETE `/api/integrations/google-drive/connection` | Authenticated | 204; delete locally stored credentials and cancel pending imports |
| GET `/api/integrations/google-drive/picker` | Authenticated | 200 `{accessToken, apiKey, appId}`; 409 not connected; 403 revoked grant; 503 not configured/unavailable |
| POST `/api/books/{bookId}/document/imports/google-drive` | UUID Idempotency-Key and JSON `{fileId}` | 202 import record and Location; 400 invalid ID; 409 not connected or operation ID reused for another file |
| GET `/api/books/{bookId}/document/imports/{importId}` | Authenticated import owner | 200 `{importId,bookId,fileId,status,documentId,message}`; 404 inaccessible operation |

Example authenticated API calls (OAuth browser steps cannot be completed with a
standalone curl request unless its browser-binding cookie is also preserved):

```sh
curl -X POST "$API/api/integrations/google-drive/connect" -H "Authorization: Bearer $ACCESS_TOKEN"
curl "$API/api/integrations/google-drive/connection" -H "Authorization: Bearer $ACCESS_TOKEN"
curl "$API/api/integrations/google-drive/picker" -H "Authorization: Bearer $ACCESS_TOKEN"
curl -X POST "$API/api/books/1/document/imports/google-drive" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H "Idempotency-Key: $IMPORT_UUID" \
  -H 'Content-Type: application/json' -d '{"fileId":"selected-drive-file-id"}'
curl "$API/api/books/1/document/imports/$IMPORT_UUID" -H "Authorization: Bearer $ACCESS_TOKEN"
curl -X DELETE "$API/api/integrations/google-drive/connection" -H "Authorization: Bearer $ACCESS_TOKEN"
```

The backend exchanges the code using PKCE and a single-use, ten-minute state bound
to the initiating account and browser cookie. Refresh credentials are encrypted
with AES-256-GCM and account identity as authenticated data. OAuth secrets and
refresh credentials never reach the frontend. Picker receives only an in-memory,
short-lived Drive access token and a public API key restricted by origin/API.

The worker refreshes the grant, verifies file metadata/download permission,
streams the PDF through the normal size/validation/storage pipeline, and creates
an immutable document. Imported content remains usable after disconnect or file
deletion in Drive. Unsupported, deleted and inaccessible files fail with safe
messages; transient Google errors are retried up to three attempts with delays.
A RUNNING job can be reclaimed after a 15-minute lease following worker failure;
its operation ID prevents duplicate document creation. Only one import is processed
at a time per application instance. Disconnect cancels pending imports; an already
running import may finish. Google-side consent can be revoked in account settings.

Use the same browser origin for Angular's API proxy and the OAuth callback so the
binding cookie accompanies the redirect. Cross-origin cookie deployment and native
Android browser handoff are not implemented by this Angular flow. Terminate HTTPS
at a trusted proxy that correctly sets the servlet's secure scheme; do not enable
untrusted forwarded headers. The cookie is Secure when the request is secure.

## Storage and deployment

### Phase 5 storage boundary

`FileStorageService` supports upload, repeatable streamed download, idempotent
delete and existence checks. Its provider identifier is persisted with each
document. Upload accepts a caller-owned stream and positive byte limit; it returns
only an opaque generated key, byte count and SHA-256 checksum. Storage does not
close the caller's input; callers close downloaded streams. No new library is
required.

`LocalFileStorageService` copies through a fixed 64 KiB buffer and checks the size
before writing each block. It writes a temporary file in the storage directory,
then atomically moves the finished file to a generated UUID key. Empty, oversized
and interrupted uploads remove their temporary files. Invalid limits fail before
reading input. Keys must match the generated UUID/PDF format; filesystem paths
are rejected. Download and existence checks reject symbolic links; deleting a
link removes the link, not its target. Keep the storage directory writable only
by the application/trusted operators.

Storage upload rejections use `StorageUploadException` rather than HTTP-specific
exceptions. `BookDocumentService` translates empty/oversized uploads into the
existing 400/413 responses and upload I/O failure into a safe 503 response.
Workspace authorization, filename sanitization, extension/MIME checking, PDF
content validation and metadata transactions remain in the document layer.
The API never accepts storage keys or returns filesystem paths.

No access-URL method is needed for private local files: authorized content is
served through the existing streaming endpoint. A future S3/R2/MinIO provider
must implement the same bounded upload and repeatable resource contract; signed
URLs would require a separate authorized, short-lived issuance flow. This phase
does not add an object-store implementation or credentials.

Phase 5 verification: all 42 backend tests passed, with none skipped. Six new
storage tests cover bounded reads, exact-limit uploads, checksums, repeatable
downloads, unique keys, idempotent deletion, invalid limits/keys, failed-upload
cleanup and symbolic links. Existing integration tests verify PDF validation,
workspace isolation and authorized range serving through the storage interface.

PDF bytes live outside PostgreSQL, in the `book-files` Compose volume mounted at
`/app/data`. The non-root container user owns that directory. Source mode defaults
to ignored `./data/books`. Never serve this directory as static/public content.
Back up both PostgreSQL and the file volume. A database backup alone cannot restore PDFs.

| Variable | Default / purpose |
| --- | --- |
| `BOOK_STORAGE_DIRECTORY` | `./data/books` in source mode; Compose sets `/app/data/books` |
| `BOOK_MAX_FILE_SIZE` | `200MB`; also the servlet file limit |
| `BOOK_MAX_REQUEST_SIZE` | `201MB`; allow multipart overhead above the file limit |
| `BOOK_MAX_PAGES` | `20000` |
| `BOOK_WORKSPACE_STORAGE_LIMIT` | `5GB`; includes all retained versions |
| `GOOGLE_DRIVE_ENABLED` | `false` |
| `GOOGLE_DRIVE_CLIENT_ID` | OAuth web client ID |
| `GOOGLE_DRIVE_CLIENT_SECRET` | Backend-only secret |
| `GOOGLE_DRIVE_REDIRECT_URI` | Exact registered callback URI |
| `GOOGLE_DRIVE_ENCRYPTION_KEY` | Separate, stable base64 encoding of 32 random bytes |
| `GOOGLE_DRIVE_PICKER_API_KEY` | Public key restricted to Picker and frontend origins |
| `GOOGLE_DRIVE_PROJECT_NUMBER` | Google project number used by Picker |

Generate the encryption key with `openssl rand -base64 32`; keep it outside source
control and separate from JWT_SECRET. All replicas need the same encryption key.
Rotating it requires re-encrypting stored credentials or reconnecting Drive accounts.

Multipart staging spills directly to disk. Stored files are copied in 64 KiB blocks;
PDFBox reads from disk to parse page count. Serving uses Spring range resources.
Allow sufficient temporary disk and proxy upload/body timeouts. Two parser permits
limit concurrent parsing per instance; excess requests receive 503 for retry.

There is no S3 implementation yet. The storage interface supports a future provider,
including ranged resources or signed URLs. Multiple local-storage replicas must
share the same protected volume; otherwise use object storage before scaling.
Signed URLs would reduce backend bandwidth but remain usable until expiry and
require explicit issuance and expiry policy.

Inactive versions are retained and charged to the workspace budget. No automatic
retention job deletes files. Failed normal uploads are cleaned up, but a process
crash can leave an orphan `.part` or UUID file. Reconcile during maintenance with
the application stopped: compare storage keys against **all** book_documents rows,
not just active rows, and remove only confirmed unreferenced files after a grace
period. Metadata deletion does not automatically delete filesystem objects.

## Angular implementation

The existing `../booker-ui` client retains its Basic login flow and in-memory
credentials. New focused services share authorization and document/progress calls.
`HttpClient` uses XHR for upload progress. Retrying an upload reuses its UUID. The
book list uses batch summaries and a collapsed Manage PDF panel; the existing
manual completion toggle is unchanged.

A lazy `/books/:bookId/read` route loads PDF.js and its matching self-hosted worker,
fonts, CMaps and WASM assets. It fetches authenticated ranges and renders one page
at a time, with previous/next/page entry controls and a page-text alternative.
It does not enable PDF scripting. The reader starts from the saved resume page.

Page changes update tab-local state immediately, save after 1.2 seconds, retry every
15 seconds and on reconnect, and attempt save on hiding/leaving the reader. Pending
operations retain their exact body/UUID after network errors. Browser shutdown
cannot guarantee an HTTP request completes: state is intentionally tab-local, not
a durable offline browser database. Already fetched PDF pages may remain readable
in the open reader; uncached pages need connectivity. No complete offline PDF cache
or service worker is claimed. A conflicting server revision requires an explicit
resume choice. Persisted percentages always come from the backend.

## Android status and integration contract

No matching Android client was identified in the available workspace. No Android
source, Retrofit/OkHttp integration, ViewModel, reader, cache or tests were added.
Do not treat the Spring project named `android` as an Android application.

Once the client location is provided, reuse its existing stack to select a PDF
URI, stream multipart data with an upload operation UUID, cache content in
app-private storage keyed by account/book/document version, render the saved page,
and persist unsynchronized progress locally. Retain exact operation bodies for
network retries, then handle 409 conflicts as above. Native OAuth needs a secure
browser handoff; never embed a Google OAuth client secret in the app.

## Security review and tests

Implemented checks: authentication, workspace/user scoping, generated path-safe
storage keys, no public storage paths, size/quota limits, filename sanitization,
PDF signature/parsing, rejection of encrypted PDFs, document/page scripts,
embedded-file name trees and unsafe nested actions/XFA. Parser traversal is capped.
Credential encryption, state expiry/replay/browser binding, fixed Google API hosts,
selected-file access and retry limits are covered by tests.

PDF validation is not an antivirus or a hard process-level CPU/memory sandbox.
Hostile-document deployments should add an isolated scanning/parser service and
malware scanning before activation. Gateway limits for upload/import/authentication
remain an operator responsibility; there is no new distributed rate limiter.

Backend tests cover multipart upload, metadata, complete and ranged reads/HEAD,
invalid ranges, missing authentication, cross-workspace access, per-user progress,
invalid/oversized/encrypted/scripted PDFs, replacement failure, storage quota,
idempotency, progress math, completion, concurrent updates, CORS and OpenAPI.
Drive tests simulate OAuth state/cookie/expiry, encrypted credentials, revoked
grants, successful imports, unsupported files, rate limits and HTTP responses.
Live Google consent/Picker/import requires configured credentials and has not been
verified against a real account.

Angular tests cover existing bookshelf behavior, upload progress/retry, saved-page
reader initialization, errors, offline retry identities, conflict resolution and
clearing account state. Android tests are pending client availability.

Run backend tests against a dedicated PostgreSQL database (see README); run
`npm test -- --watch=false` and `npm run build` in `booker-ui`. Existing CSS budget
warnings are independent of the new reader.
