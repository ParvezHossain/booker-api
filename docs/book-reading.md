# PDF documents and personal reading progress

This document describes private-library personal progress. The additive global
Public Library uses workspace-shared progress; see [public-library.md](public-library.md).
Private PDF limits and API paths remain unchanged.

## Architecture and compatibility

The existing Spring MVC/JPA/JDBC architecture is retained. Books belong to a
workspace and keep their existing metadata, creation API, `BookResponse` and
`book.created` notification. `Book.completed` remains the existing metadata flag.
It is **not** overwritten by personal PDF completion.

Feature packages:

- `document`: workspace book access, document metadata repository, PDF validation,
  upload service and streaming controller.
- `storage`: `FileStorageService` and private local filesystem implementation.
- `reading`: per-account progress, concurrency and batch summaries.
- `drive`: Google OAuth, credential encryption, API gateway and durable import queue.

Both existing HTTP Basic and JWT bearer authentication work on the document and progress APIs.
Workspace identity and account email always come from `WorkspacePrincipal`.
Books/files are workspace-shared; progress and Drive credentials are per account.
Another workspace's book/document returns 404. Google import status also requires
that the requesting account created the import.

## Schema and versioning

### Document model

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

### Migrations and indexes

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

V6 and V8 do not rewrite existing book metadata. Books without a document remain valid.
V9 changes the historical book identity contract; see the upgrade preflight in
[operations](operations.md#database-migrations-and-upgrades).
The project has no Flyway undo convention. Back up the database and file volume
before deployment; use a forward migration or restore both backups for rollback.
Do not remove migration history entries on a running installation.

## Progress semantics and synchronization

### Progress persistence

Progress belongs to an authenticated account and a book, using the existing
`workspace_users.email` identity rather than introducing a separate user ID.
V6 defines the account/book constraints independently of the `Book` entity.

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

## Completion semantics

Both completion concepts remain separate:

| Field | Meaning and scope |
| --- | --- |
| `Book.completed` | Manually supplied workspace book metadata, accepted by `BookRequest` on creation and returned by existing book APIs |
| `ReadingProgress.completed` | Derived per account and active document: `max_page_reached == page_count` |

`BookService.createBook` persists the caller's metadata flag. Private metadata has
no update endpoint. Super Admin can update public metadata through its full PUT,
independently of workspace reading progress. Reading never automatically writes
`books.completed`.

Reaching the last page completes personal reading. Returning to an earlier page
changes the resume position but retains completion. A stale update that merges
the final page can also complete personal reading, while preserving the accepted
resume position and last-read timestamp. Another account's progress is unaffected.
Replacing the PDF resets visible personal completion for the new document, even
if the book's manual metadata says it is completed. Books without PDFs can retain
either value of the manual flag.

Completion is based on exact page counts, never rounded percentage. For example,
19999/20000 pages rounds to 100.00% at two decimal places but is still incomplete.
Clients must use the `completed` boolean for completion badges. An unopened
single-page document is incomplete; saving page 1 completes it. These are page
navigation semantics, not proof that every preceding page was read. No database
migration or backfill is needed.

## HTTP contracts

The [private PDF API](../API.md#9-private-pdf-documents) and
[progress API](../API.md#10-private-reading-progress) define methods, request fields,
responses, validation and examples. Public equivalents are in
[the public API](../API.md#13-public-library).

Upload uses multipart `file` and a UUID Idempotency-Key; public direct upload uses
raw application/pdf with fileName. Metadata is created before a private upload.
Upload returns document metadata, never the PDF. Retries identify the original
operation without comparing uploaded bytes, so retain the same file/body/key.
A historical replay can return inactive metadata; refetch active document/progress.

GET content uses authenticated Spring Resource streaming and byte ranges without
loading the full PDF into an application byte array. Prefix/suffix/open-ended ranges
are supported; unsatisfiable ranges return 416. Each request reauthorizes the book
before range/version handling. Optional documentId pins the active version, not a
historical version; a replacement returns 409. download=true selects attachment.
Responses include application/pdf, version ETag, UTF-8 Content-Disposition,
Accept-Ranges, no-store and nosniff. If-Range has no application-specific handling;
use documentId pinning for safe resumable downloads.

Explicit HEAD authorizes and checks content availability without opening bytes,
returns complete 64-bit length and ignores Range. Missing metadata is 404; missing
stored bytes are a safe 503. A failure after streaming headers may interrupt content
rather than return JSON; clients must verify downloaded length/checksum and retry.
Protected offline files are a deliberate client feature, not a shared HTTP cache.

Reading summary queries join book/document/progress once, accept 1–100 IDs, and fail
if any requested ID is inaccessible. Missing documents produce null document/progress.
Progress revision conflicts return ReadingProgress at 409; replacement/reused-operation
conflicts return ApiError. Security/proxy/Range/HEAD failures have their own body rules.

## Google Drive APIs and setup

### OAuth and import behavior

The existing OAuth/Picker/import flow remains the integration boundary. OAuth uses
PKCE and single-use browser-bound state; account-bound AES-256-GCM protects stored
refresh credentials. Only `drive.file` is requested. The backend refreshes access
tokens and verifies the selected file's MIME type, size and download capability
before streaming it through the normal PDF validation and private-storage pipeline.
Only file IDs are accepted; arbitrary Drive URLs are rejected. Imported PDFs
remain readable independently of the original Drive file or account connection.

The gateway recognizes both `rateLimitExceeded` and `userRateLimitExceeded` inside
403 responses, as described in Google's
[Drive error guide](https://developers.google.com/workspace/drive/api/guides/handle-errors).
These become retryable 503 errors, as do HTTP 429 and server failures. Other 403
responses remain permission denials. Error-body inspection is capped at 16 KiB;
upstream error details and credentials are never returned to clients. Malformed
or oversized 403 bodies remain denials.

Transient imports retry after 30 then 60 seconds, with at most three ordinary
attempts. Replaying an existing import operation returns its current status even
after disconnect, but starting a new import still requires a connected account.
Reusing that operation for a different file is rejected. Disconnect cancels pending
imports; jobs already running may finish. Import status is private to the account
that initiated it, including within a shared workspace.

Live Google consent, Picker and imports still require deployment-specific OAuth
credentials and browser verification. Automated tests use simulated Google
responses and do not establish that a live account has been connected.

Configuration and exact OAuth/Picker setup are maintained in
[operations](operations.md#google-drive). The
[Drive APIs](../API.md#11-google-drive) define connection, callback, Picker, import
start/status and disconnect contracts. Import start returns 202 and a Location;
clients poll until COMPLETED/FAILED rather than treating acceptance as success.

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
binding cookie accompanies the redirect. A cross-origin cookie deployment and native
Android handoff require separately reviewed client/backend integration. Terminate HTTPS
at a trusted proxy that correctly sets the servlet's secure scheme; do not enable
untrusted forwarded headers. The cookie is Secure when the request is secure.

## Storage and deployment

### Storage boundary

`FileStorageService` supports upload, repeatable streamed download, idempotent
delete and existence checks. Its provider identifier is persisted with each
document. Upload accepts a caller-owned stream and positive byte limit; it returns
only an opaque generated key, byte count and SHA-256 checksum. Storage does not
close the caller's input; callers close downloaded streams.

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
URLs would require a separate authorized, short-lived issuance flow. Object-store implementations and signed URLs are proposed work, not current capabilities.

PDF bytes live outside PostgreSQL, in the `book-files` Compose volume mounted at
`/app/data`. The non-root container user owns that directory. Source mode defaults
to ignored `./data/books`. Never serve this directory as static/public content.
Back up both PostgreSQL and the file volume. A database backup alone cannot restore PDFs.

Storage limits, directories and provider secrets are maintained in
[operations](operations.md#configuration). Private defaults are 200 MB/file,
201 MB/multipart request, 20,000 pages and 5 GB/workspace including retained versions.
Public raw upload bypasses those quotas; infrastructure capacity still applies.

Generate the encryption key with `openssl rand -base64 32`; keep it outside source
control and separate from JWT_SECRET. All replicas need the same encryption key.
Rotating it requires re-encrypting stored credentials or reconnecting Drive accounts.

Multipart staging spills directly to disk. Stored files are copied in 64 KiB blocks;
PDFBox reads from disk to parse page count. Serving uses Spring range resources.
Allow sufficient temporary disk and proxy upload/body timeouts. A shared HTTP
admission gate defaults to four uploads per instance, after security but before
multipart parsing or raw body reads. It covers private uploads, public uploads and
request acceptance; overload returns 503 ApiError with Retry-After seconds.
Two parser permits default to a bounded five-second wait; timeout returns 503 with
Retry-After and cleans up staged storage. Parser concurrency/wait are configurable
and also apply to Drive imports. See [resource budgets and traffic checks](operations.md#pdf-traffic-and-small-server-deployment).

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
period. Public book deletion queues all retained files for durable cleanup. Direct SQL
metadata deletion does not provide that application workflow; avoid bypassing it.

## Client integration boundary

Android and Angular application sources are not included in this repository.
[Android](../ANDROID_PROMPTS.md) and [Angular](../ANGULAR_PROMPTS.md) specifications
cover streaming upload/download, reader page restoration, local pending state,
account isolation and revision conflicts. They are requirements for separate
clients, not proof of deployed features or client test results.

A browser needs an authenticated Range-capable reader and same-origin OAuth
callback routing. A native reader needs a complete verified local PDF or a tested
remote rendering adapter, durable account-scoped reading state, and a secure
browser handoff for Drive. Retrofit's cookie jar is not a browser cookie jar.
Temporary network loss must not reset a locally known reading position.

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
remain an operator responsibility; there is no distributed rate limiter.

Backend tests cover multipart upload, metadata, complete and ranged reads/HEAD,
invalid ranges, missing authentication, cross-workspace access, per-user progress,
invalid/oversized/encrypted/scripted PDFs, replacement failure, storage quota,
idempotency, progress math, completion, concurrent updates, CORS and OpenAPI.
Drive tests simulate OAuth state/cookie/expiry, encrypted credentials, revoked
grants, successful imports, unsupported files, rate limits and HTTP responses.
Live Google consent/Picker/import requires configured credentials and has not been
verified against a real account.

Run backend verification against a disposable PostgreSQL database as described in
[operations](operations.md#testing). Drive tests simulate upstream responses;
live consent, Picker and import require a separate deployment smoke test. Client
SDK/browser tests belong to the corresponding client repository.
