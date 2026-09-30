# Booker API reference and Android implementation checklist

Source review date: 2026-09-30. Author/title identity update: 2026-09-30. This reference describes the current backend code, not the proposed endpoints in AGENTS.md. Use it with your Android Studio AI to audit networking, screens, reader behavior, retries, and feature availability.

**Public Library validation:** all 92 backend tests passed with none skipped, including real HTTP upload/streaming and private multipart-limit checks, migration/role/progress/cleanup tests, and all prior private regressions. The Maven backend package build passed. No frontend tests or code changes are part of this backend feature.

**Verification scope:** controllers, DTOs, services, repositories, security, OpenAPI annotations, configuration, migrations, existing tests, backend documentation, and the adjacent Angular API service were inspected. The initial request to `http://localhost:8080/v3/api-docs` could not connect. After the author/title identity update, all 74 backend tests passed against an isolated PostgreSQL 18 database (none skipped), including generated OpenAPI/MockMvc contract, migration and concurrency checks. All 13 adjacent Angular tests and its production build passed (existing CSS budget warning). This is local test evidence, not a deployed endpoint or live Google OAuth verification.

## 1. Project architecture

- This directory is a **backend**, despite its name `android`: Java 25, Spring Boot 4.1.1, Spring MVC, Spring Security, Spring Data JPA/JDBC, PostgreSQL, Flyway V1–V10, springdoc 3.1.0, and PDFBox 3.0.8.
- Private books belong to workspaces; public books are global with no workspace owner. Users are stored in `workspace_users` with email as their account key; there is no separate JPA User/Workspace entity. Signup creates a workspace and its owner.
- `Book` is a JPA entity with an identity-generated numeric ID. Workspace/document/import IDs are UUIDs. Document, progress, account, and import persistence also uses JDBC.
- Workspace identity and user email come from authentication. Do not send a workspace header or workspaceId to select another tenant.
- PDFs are private local files behind `FileStorageService`; PostgreSQL contains metadata/references, not PDF binaries. `LOCAL` is the implemented provider; S3 and signed URLs are not implemented.
- A book can have multiple retained immutable PDF versions, with at most one active version. Public reader endpoints serve only the active version.
- Private reading progress is per account/book; public progress is shared per workspace/book, both tied to the active document. Private books/PDFs are workspace shared; public books/PDFs are globally available to authenticated accounts. Drive credentials and import status are account private.
- The adjacent `../booker-ui` contains Angular 22.2, HttpClient, a PDF.js reader, and upload/progress/Drive integration. These files were inspected for integration conventions, but their runtime behavior was not tested here.
- No native Android source was identified in this backend. Retrofit, OkHttp, Compose/XML UI, ViewModels, offline cache, and Android feature completeness must be checked in the actual Android Studio project.

## 2. Connection, authentication, and Swagger

Default backend base URL: `http://localhost:8080`; deployment can change port/host. Android must use a host reachable from its device/emulator; `localhost` on Android refers to that device. Use HTTPS in deployment.

Business URLs use `/api` with **no `/v1` prefix**. OpenAPI's metadata version `2.1.0` is not a URL prefix.

Protected operations below accept either:

```http
Authorization: Bearer <accessToken>
```

or legacy HTTP Basic with a registered owner's email/password. Use bearer tokens for the Android integration. Never send refresh tokens as bearer access credentials or place tokens in URLs.

Public business operations: signup, login, refresh, logout, and the Google OAuth callback. Refresh/logout still require a valid refresh credential in their body. All book/workspace/other Drive operations require authentication.

| Documentation/infrastructure path | Access | Purpose |
| --- | --- | --- |
| GET `/swagger-ui/index.html` | Public | Interactive Swagger UI |
| GET `/swagger-ui.html` | Public | springdoc UI entry path |
| GET `/v3/api-docs` | Public | Generated OpenAPI JSON |
| GET `/v3/api-docs.yaml` | Public | springdoc YAML specification |
| GET `/v3/api-docs/swagger-config` | Public | Swagger UI configuration |
| GET `/actuator/health` | Public | Health check; commonly 200 with `{"status":"UP"}`, unhealthy status depends on health configuration |
| GET `/` | Denied by current security policy | `HomeController` contains `Hello World!`, but this route is not publicly usable; security uses `anyRequest().denyAll()` |

Swagger assets, implicit framework HEAD/OPTIONS behavior, and error dispatches are not separate business APIs. The PDF HEAD operation below is explicitly implemented.

Swagger workflow:

1. Open `/swagger-ui/index.html`.
2. Call **Workspaces → POST /api/auth/signup**.
3. Call **Authentication → POST /api/auth/login**.
4. Paste only the returned access token into **Authorize → bearerAuth**.
5. Test workspace/books, then PDF/progress/Drive operations. Use an SSE-capable client for notifications.
6. Download `/v3/api-docs` from your running deployment and compare its paths against the inventory below.

## 3. Complete business API inventory

There are **35 business method/path operations**: the existing 23 private/authentication/Drive operations plus 12 Public Library operations. Search variants are query parameters of one operation.

| Step | Method | Path | Authentication | Success |
| --- | --- | --- | --- | --- |
| 1 | POST | `/api/auth/signup` | Public | 201 SignupResponse |
| 2 | POST | `/api/auth/login` | Public; email/password body | 200 Tokens |
| 3 | POST | `/api/auth/refresh` | Refresh token body | 200 Tokens |
| 4 | POST | `/api/auth/logout` | Refresh token body | 204 |
| 5 | GET | `/api/workspace` | Bearer or Basic | 200 WorkspaceResponse |
| 6 | POST | `/api/books` | Bearer or Basic | 201 BookResponse |
| 7 | GET | `/api/books` | Bearer or Basic | 200 BookResponse[] |
| 8 | GET | `/api/books/{bookId}` | Bearer or Basic | 200 BookResponse |
| 9 | GET | `/api/books/events` | Bearer or Basic | 200 SSE stream |
| 10 | POST | `/api/books/{bookId}/document` | Bearer or Basic | 201 DocumentResponse |
| 11 | GET | `/api/books/{bookId}/document` | Bearer or Basic | 200 DocumentResponse |
| 12 | GET | `/api/books/{bookId}/document/content` | Bearer or Basic | 200 PDF / 206 range |
| 13 | HEAD | `/api/books/{bookId}/document/content` | Bearer or Basic | 200 headers, no body |
| 14 | GET | `/api/books/{bookId}/reading-progress` | Bearer or Basic | 200 ReadingProgress |
| 15 | PUT | `/api/books/{bookId}/reading-progress` | Bearer or Basic | 200 ReadingProgress |
| 16 | GET | `/api/books/reading-summaries` | Bearer or Basic | 200 Summary[] |
| 17 | POST | `/api/integrations/google-drive/connect` | Bearer or Basic | 200 authorization URL + cookie |
| 18 | GET | `/api/integrations/google-drive/callback` | OAuth state + binding cookie | 200 HTML |
| 19 | GET | `/api/integrations/google-drive/connection` | Bearer or Basic | 200 connection state |
| 20 | DELETE | `/api/integrations/google-drive/connection` | Bearer or Basic | 204 |
| 21 | GET | `/api/integrations/google-drive/picker` | Bearer or Basic | 200 Picker configuration |
| 22 | POST | `/api/books/{bookId}/document/imports/google-drive` | Bearer or Basic | 202 ImportStatus |
| 23 | GET | `/api/books/{bookId}/document/imports/{importId}` | Bearer or Basic; import owner | 200 ImportStatus |
| 24 | GET | `/api/public-books` | Bearer or Basic | 200 BookResponse[] |
| 25 | GET | `/api/public-books/{bookId}` | Bearer or Basic | 200 BookResponse |
| 26 | POST | `/api/public-books` | Super Admin | 201 BookResponse |
| 27 | PUT | `/api/public-books/{bookId}` | Super Admin | 200 BookResponse |
| 28 | DELETE | `/api/public-books/{bookId}` | Super Admin | 204; file cleanup queued |
| 29 | POST | `/api/public-books/{bookId}/document` | Super Admin; raw PDF body | 201 DocumentResponse |
| 30 | GET | `/api/public-books/{bookId}/document` | Bearer or Basic | 200 DocumentResponse |
| 31 | GET | `/api/public-books/{bookId}/document/content` | Bearer or Basic | 200 PDF / 206 range |
| 32 | HEAD | `/api/public-books/{bookId}/document/content` | Bearer or Basic | 200 headers |
| 33 | GET | `/api/public-books/{bookId}/reading-progress` | Workspace account | 200 ReadingProgress |
| 34 | PUT | `/api/public-books/{bookId}/reading-progress` | Workspace account | 200 ReadingProgress |
| 35 | GET | `/api/public-books/reading-summaries` | Workspace account | 200 Summary[] |

`bookId` is the numeric `BookResponse.id`, not a UUID. `documentId`, `importId`, operation IDs, and `Idempotency-Key` are UUID strings.

## 4. Step-by-step endpoint contracts

All JSON request examples use `Content-Type: application/json`. Protected endpoints require the Authorization header. Standard application errors are described in section 6; authentication failures can be generated by Spring Security with a different or empty body.

### Step 1 — Create a workspace/account

**POST `/api/auth/signup`** — public.

```json
{"workspaceName":"My Library","email":"owner@example.com","password":"replace-this-password"}
```

Required: nonblank workspaceName (max 100), valid nonblank email (max 254), nonblank password (12–64 characters). Email is stripped/lowercased; workspace name is stripped. Creates an empty FREE workspace with a 100-book limit.

201 example:

```json
{"workspaceId":"11111111-1111-4111-8111-111111111111","workspaceName":"My Library","email":"owner@example.com","plan":"FREE"}
```

Errors: 400 invalid fields/JSON; 409 duplicate email/database constraint. Signup does not return tokens; log in next.

### Step 2 — Log in

**POST `/api/auth/login`** — public, credentials in body.

```json
{"email":"owner@example.com","password":"replace-this-password"}
```

Email: nonblank valid email, max 254. Password: nonblank, max 64. Signup's minimum password length is not separately enforced on the login DTO.

200 example (placeholder tokens):

```json
{"accessToken":"<jwt-access-token>","refreshToken":"<jwt-refresh-token>","tokenType":"Bearer","expiresIn":900,"refreshExpiresIn":604800}
```

Errors: 400 invalid body; 401 incorrect credentials. Lifetimes are seconds and configurable: defaults 15 minutes/7 days. Response has `Cache-Control: no-store` and `Pragma: no-cache`. Each login creates an independent refresh session.

### Step 3 — Refresh the session

**POST `/api/auth/refresh`** — no Authorization header required.

```json
{"refreshToken":"<current-refresh-token>"}
```

Required nonblank refreshToken, max 4096. 200 returns the same Tokens schema as login. **Replace both stored tokens atomically:** the old refresh token is consumed; concurrent use succeeds only once. Serialize Android refresh requests. An uncertain network failure during refresh can require login again because the old token may already have been consumed.

Errors: 400 invalid body; 401 invalid, expired, revoked, already consumed, or wrong token type.

### Step 4 — Log out

**POST `/api/auth/logout`** — no Authorization header required; same Refresh body.

204, no response body, no-store. Revokes that refresh token only; issued access tokens remain usable until expiry. A repeat with an otherwise valid, unexpired refresh JWT is safe even if its row was already deleted. Clear local Android tokens/cache/account state.

Errors: 400 invalid body; 401 invalid/expired/wrong-type refresh JWT.

### Step 5 — Get workspace/plan usage

**GET `/api/workspace`** — authenticated, no body/query.

200 example:

```json
{"id":"11111111-1111-4111-8111-111111111111","name":"My Library","plan":"FREE","book_limit":100,"books_used":1}
```

The underscore fields are intentional; do not map them as `bookLimit`/`booksUsed` without JSON annotations. Plan can be FREE/PRO. There is no payment/upgrade API. Errors: 401.

### Step 6 — Create book metadata

**POST `/api/books`** — authenticated, current workspace.

```json
{"title":"Effective Java","author":"Joshua Bloch","publishedDate":"2018","description":"Java best practices","completed":false}
```

Required: nonblank title/author (max 255 each), nonblank publishedDate string (max 20). Optional description max 5000; send `completed` explicitly as a boolean. Publication date is a free-form string, not a required ISO date.

201 example:

```json
{"id":1,"title":"Effective Java","author":"Joshua Bloch","publishedDate":"2018","description":"Java best practices","completed":false}
```

`Location` points to `/api/books/1` (normally an absolute URL generated from the request). Creation commits a `book.created` event with the book. The exact, case-sensitive author/title pair is unique per workspace. Save the numeric ID for document/progress calls.

Errors: 400 validation/JSON; 401; 403 workspace book quota reached; 409 duplicate workspace author/title pair. This creates metadata only, not a PDF.

### Step 7 — List/search books

**GET `/api/books`** — authenticated.

Optional query parameters: `author`, `title`. Examples:

```text
/api/books
/api/books?author=Joshua%20Bloch
/api/books?title=Effective%20Java
/api/books?author=Joshua%20Bloch&title=Effective%20Java
```

Filters use exact equality; both supplied means AND. Results are workspace scoped, unpaginated BookResponse arrays; no matches returns `[]`. No documented sorting or pagination parameters. PDF/progress data is not embedded; use step 16.

Errors: 401; 500 server failure.

### Step 8 — Get a book by numeric ID

**GET `/api/books/{bookId}`** — authenticated, workspace scoped.

200 BookResponse (step 6). 400 malformed numeric ID; 401 missing/invalid credentials; 404 missing ID or another workspace's book. Use BookResponse.id for lookup and creation's Location URL. Author/title exact search remains available through GET /api/books.

### Step 9 — Subscribe to book-created notifications

**GET `/api/books/events`** — authenticated, `Accept: text/event-stream`.

Optional header `Last-Event-ID`: opaque nonnegative decimal string. Omit for future events; `0` replays all retained events for this workspace; a saved ID replays subsequent events then continues live.

200 stream example:

```text
id: 17
event: ready
retry: 3000
data: {}

id: 18
event: book.created
data: {"eventId":"18","type":"book.created","schemaVersion":2,"occurredAt":"2026-09-26T12:00:00Z","book":{"id":1,"title":"Effective Java","author":"Joshua Bloch","publishedDate":"2018","description":null,"completed":false}}

```

Persist ready/event IDs after successful handling. IDs may have gaps; deduplicate replay. Heartbeats are comments approximately every 15 seconds; connections expire after five minutes. Reconnect with backoff/jitter and last cursor. Stream is no-cache/no-store with `X-Accel-Buffering: no`.

Errors: 400 malformed/negative/future cursor; 401; 503 capacity/service unavailable (default max 200 connections per instance). SSE is not FCM or an OS push notification, and does not wake a closed Android app.

### Step 10 — Upload/replace a PDF

**POST `/api/books/{bookId}/document`** — authenticated and workspace authorized.

Required header: `Idempotency-Key: <UUID>`. Required multipart part: `file`, original filename ending in `.pdf`, part MIME `application/pdf`. Request content type is `multipart/form-data` with a generated boundary. Do not manually force a boundary-free multipart header.

```sh
curl -X POST "$BASE_URL/api/books/$BOOK_ID/document" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Idempotency-Key: $UPLOAD_OPERATION_UUID" \
  -F 'file=@/path/to/book.pdf;type=application/pdf'
```

201 DocumentResponse example (checksum abbreviated only for readability):

```json
{"bookId":1,"documentId":"22222222-2222-4222-8222-222222222222","fileName":"book.pdf","fileSize":12345678,"mimeType":"application/pdf","pageCount":144,"checksum":"<64-character-sha256-hex>","sourceType":"UPLOAD","active":true,"createdAt":"2026-09-30T08:00:00Z"}
```

`Location: /api/books/{bookId}/document`; no-store. Returns metadata, never file bytes/storage paths. Server checks filename/MIME, bounds byte size, validates PDF content and parses page count. Defaults: file 200MB, multipart request 201MB, PDF 20000 pages, retained workspace documents total 5GB. Extension comparison is case insensitive. Encrypted/unsafe/unsupported PDFs are rejected.

Replacement creates a new version and deactivates the old version only after successful validation/transaction. Existing PDF survives a failed replacement. Active-document personal progress resets logically on replacement. Old files are retained and count against quota.

Retries must reuse the same UUID **for the same file and book/account**. Upload replay returns the original metadata with 201, potentially an inactive old version after later replacement. The backend does not compare a replayed upload's new bytes to the original; never reuse a key for a different file. Refetch active metadata after success/replay.

Errors: 400 missing file/header, malformed UUID, invalid filename, empty file; 401; 404 inaccessible/missing book; 403 storage budget; 413 size; 415 unsupported/unsafe PDF or content type; 503 storage/parser unavailable.

### Step 11 — Get active PDF metadata

**GET `/api/books/{bookId}/document`** — authenticated and workspace authorized.

200 DocumentResponse (step 10), no-store. `sourceType` is UPLOAD or GOOGLE_DRIVE. Missing PDF/book or another workspace's book: 404; authentication: 401.

No raw binary, filesystem path, storage key, credentials, or signed URL is returned.

### Step 12 — Read/download PDF bytes

**GET `/api/books/{bookId}/document/content`** — authenticated and workspace authorized.

Optional queries: `download` boolean (default false), `documentId` UUID. Supply documentId from metadata to prevent silently reading a replaced version; it pins the **active** version, not archived access.

Optional header: `Range: bytes=0-65535`. Full read returns 200; satisfiable range returns 206. Binary PDF response, not JSON.

```sh
curl "$BASE_URL/api/books/$BOOK_ID/document/content?documentId=$DOCUMENT_ID" \
  -H "Authorization: Bearer $ACCESS_TOKEN" -H 'Range: bytes=0-65535' \
  -o first-range.bin
```

Response headers include `Content-Type: application/pdf`, `Accept-Ranges: bytes`, `Content-Disposition: inline` (attachment for download=true), `ETag` containing document UUID, no-store, and `X-Content-Type-Options: nosniff`. Partial responses include byte range headers. Content streams through an authorized Resource rather than a full in-memory byte array. Range handling is delegated to Spring MVC.

Errors: 400 malformed parameters; 401; 404 missing/inaccessible book/PDF; 409 pinned document replaced; 416 invalid/unsatisfiable range; 503 missing/unavailable stored content. Do not assume ETag implies a custom 304/If-Range contract; test the deployed framework behavior if needed.

### Step 13 — Inspect PDF headers

**HEAD `/api/books/{bookId}/document/content`** — same authorization and optional queries as GET.

200 with PDF headers and complete `Content-Length`, no body. Checks content availability, ignores Range. Errors: 400, 401, 404, 409 replaced pinned document, 503 storage unavailable.

### Step 14 — Get personal progress/resume page

**GET `/api/books/{bookId}/reading-progress`** — authenticated and workspace authorized; account-private progress.

200 example:

```json
{"bookId":1,"documentId":"22222222-2222-4222-8222-222222222222","currentPage":93,"totalPages":144,"pagesRead":93,"progressPercentage":64.58,"lastReadAt":"2026-09-30T08:05:00Z","completed":false,"resumePage":93,"version":1}
```

First-open response is 200 with currentPage=0, pagesRead=0, progressPercentage=0, completed=false, resumePage=1, lastReadAt=null, version=0, and the active document's ID/page count. This read does not create a persisted progress row. No-store.

`pagesRead` means **furthest page reached**, not a count of individually visited pages. Percentage = furthest page / active PDF page count × 100, rounded half-up to two decimals. Going backward changes resume position while preserving maximum progress/completion. Personal `completed` is true after reaching the final page; it never mutates Book.completed.

Errors: 400 malformed book ID; 401; 404 inaccessible book or no active PDF.

### Step 15 — Save personal progress

**PUT `/api/books/{bookId}/reading-progress`** — authenticated and workspace authorized.

All four fields are required by the integration contract:

```json
{"documentId":"22222222-2222-4222-8222-222222222222","currentPage":93,"version":0,"operationId":"33333333-3333-4333-8333-333333333333"}
```

- documentId: active PDF UUID.
- currentPage: one-based integer, 1..totalPages; 0 is only a first-open response sentinel and cannot be saved.
- version: non-null integer >=0; use the latest server revision, 0 for first save. Omitted/null version is explicitly rejected.
- operationId: UUID for this exact update; retain the whole body unchanged for network retries.

200 returns current ReadingProgress. A successful new update increments revision and sets server lastReadAt. Replaying an accepted operation returns current progress without creating a duplicate update or advancing timestamp/revision merely for the replay. The replay may show newer state from another operation/device.

**409 must be parsed in two different ways:**

1. A revision conflict returns a **ReadingProgress body**, not ApiError. Stale revision preserves server resume position and lastReadAt, but merges a higher furthest page (which can increment version). A future revision returns current progress without mutation. Do not silently overwrite another device's resume position. Let the user choose whether to keep server position or save the intended local position with a new UUID/current revision.
2. A replaced document or reused operationId with a different book/document/page/revision returns **ApiError**. Refetch metadata/progress and reopen for replacement; correct retry identity for accidental reuse.

Errors: 400 invalid page/body/UUID/version; 401; 404 inaccessible book/PDF; 409 as above. Percentage, totalPages, completion, and user/workspace identity are calculated/derived by the server and are not request fields.

### Step 16 — Batch list progress/document summaries

**GET `/api/books/reading-summaries?bookIds=1,2,3`** — authenticated.

Required `bookIds`: 1–100 numeric IDs (comma-separated; repeated list query values are also Spring list binding). Duplicates are removed after validating input count; result is ordered by book ID. Every requested distinct book must belong to the current workspace.

200 example:

```json
[{"bookId":1,"document":{"bookId":1,"documentId":"22222222-2222-4222-8222-222222222222","fileName":"book.pdf","fileSize":12345678,"mimeType":"application/pdf","pageCount":144,"checksum":"<64-character-sha256-hex>","sourceType":"UPLOAD","active":true,"createdAt":"2026-09-30T08:00:00Z"},"progress":{"bookId":1,"documentId":"22222222-2222-4222-8222-222222222222","currentPage":93,"totalPages":144,"pagesRead":93,"progressPercentage":64.58,"lastReadAt":"2026-09-30T08:05:00Z","completed":false,"resumePage":93,"version":1}},{"bookId":2,"document":null,"progress":null}]
```

Books without PDFs have both null fields. Books with a PDF and no saved progress have first-open progress as in step 14. Use batches for a large unpaginated book list. No-store.

Errors: 400 missing/invalid/empty/>100 list; 401; 404 any missing/inaccessible book (not a partially successful list).

### Step 17 — Start Google Drive OAuth

**POST `/api/integrations/google-drive/connect`** — authenticated, no required body.

200 JSON `{"authorizationUrl":"https://accounts.google.com/o/oauth2/v2/auth?..."}` plus `Set-Cookie: booker_drive_binding=...` and no-store. Cookie is HttpOnly, SameSite=Lax, callback-path scoped, ten-minute max age, Secure when request is secure. Requests minimum `drive.file` scope using PKCE and one-time state bound to the initiating account/browser.

Open the URL in the browser holding that cookie. An Android API HTTP client's cookie jar is not automatically shared with an external browser. A secure native/browser handoff must be designed and verified; opening the URL alone does not complete the backend's binding requirement. No native handoff endpoint is implemented.

Errors: 401; 503 Drive disabled/unconfigured.

### Step 18 — Complete the OAuth callback

**GET `/api/integrations/google-drive/callback?state=...&code=...`** — Google browser redirect, no API bearer requirement.

Requires valid state, code, and matching `booker_drive_binding` cookie. State/code are optional at the controller binding level to provide a controlled error, but required for successful completion. State expires after ten minutes and is consumed once.

200 `text/html` confirmation instructing the user to close the tab and return. Clears the binding cookie; no-store. Stores encrypted refresh credentials server-side; no credential is returned to the app. The Android app checks connection state afterward, rather than parsing HTML as JSON.

Errors: 400 missing/invalid/expired/replayed/browser-mismatched state, denied/incomplete consent; 403 Google grant denied/revoked; 503 disabled/unavailable upstream. This endpoint is an OAuth callback, not a generic URL-import API.

### Step 19 — Check Drive connection

**GET `/api/integrations/google-drive/connection`** — authenticated.

200 `{"connected":true}` or false, no-store. False when disabled or no locally stored connection. True checks presence of local credentials; it does not guarantee Google has not revoked consent. Errors: 401.

### Step 20 — Disconnect Drive

**DELETE `/api/integrations/google-drive/connection`** — authenticated, no body.

204. Removes this account's local credentials and pending OAuth states; marks its pending imports FAILED. An already running import can finish. Previously imported PDFs remain readable. This does not itself revoke consent in Google account settings. Errors: 401.

### Step 21 — Get Google Picker configuration

**GET `/api/integrations/google-drive/picker`** — authenticated, account private.

200 example:

```json
{"accessToken":"<short-lived-google-drive-access-token>","apiKey":"<public-restricted-picker-key>","appId":"<google-project-number>"}
```

No-store. The returned token is a **Google token**, not the backend JWT. Keep it in memory for file selection; never persist/log it or use it as backend Authorization. Response does not include OAuth client secret/refresh token. No generic Drive file-list endpoint exists.

Errors: 401; 409 not connected; 403 grant revoked/denied (stored connection can be removed); 503 Picker/Drive unconfigured or Google unavailable/rate limited.

### Step 22 — Start a selected Drive PDF import

**POST `/api/books/{bookId}/document/imports/google-drive`** — authenticated, workspace book access and connected account.

Required `Idempotency-Key: <UUID>` and JSON:

```json
{"fileId":"selected-drive-file-id"}
```

fileId must match `[A-Za-z0-9_-]{1,200}` and be nonblank. Accepts a Google file ID, **never an arbitrary Drive URL**. Selected-file access is checked using the connected Google account in the worker.

202 example:

```json
{"importId":"44444444-4444-4444-8444-444444444444","bookId":1,"fileId":"selected-drive-file-id","status":"PENDING","documentId":null,"message":null}
```

`Location: /api/books/{bookId}/document/imports/{importId}`. Reuse the same key for retries of the same book/file/account. Replaying an existing operation returns its current status with 202, including after disconnect; reusing for a different file is rejected. A 202 means queued, **not successfully imported**.

Errors: 400 invalid file ID/header/UUID/body; 401; 404 inaccessible book/operation; 409 no Drive connection or key reused for another file. With Drive disabled a new request normally sees no connection and returns 409; enabling/configuring Drive is required for a worker to process jobs.

### Step 23 — Poll Drive import status

**GET `/api/books/{bookId}/document/imports/{importId}`** — authenticated; current account must own import, even within a shared workspace.

200 ImportStatus (step 22). States: PENDING → RUNNING → COMPLETED or FAILED. COMPLETED supplies documentId; failures supply a safe message. Retryable worker failures can return to PENDING, delayed 30 then 60 seconds, up to three ordinary attempts. Poll with backoff and stop on terminal state.

Unsupported, inaccessible/deleted, download-denied, oversized, unsafe PDFs, and revoked grants generally appear as **FAILED in the 200 status body**; they are not synchronous upload-style HTTP errors on the polling request. The import copies PDF bytes into private app storage, so later reading is independent of Drive availability.

Errors: 400 malformed IDs; 401; 404 missing/inaccessible book/import or another account's import. After COMPLETED, refetch active metadata/progress; another replacement may have superseded this document.

## 5. Android flows to audit in order

### Account and bookshelf

1. Signup → login → securely persist tokens → GET workspace.
2. GET books; retain the numeric book ID. Exact search uses the same GET endpoint.
3. Batch reading-summaries in groups of at most 100; handle null document/progress.
4. POST books to create metadata before uploading/importing. Handle 403 quota and 409 duplicate author/title pair.
5. Serialize token refresh; replace both credentials. Logout revokes refresh and clears account-scoped state.
6. Optional SSE connection with durable last event cursor, replay deduplication, refresh/reconnect handling.

### Local PDF upload and reading

1. Select a PDF URI; stream multipart from its content source. Send part filename, PDF MIME, and one persisted upload UUID per selected file. Show progress and retry the same UUID on uncertain failure.
2. Refresh active metadata and summaries after upload; do not assume replay metadata is still active.
3. Load active metadata + personal progress; compare document IDs if these separate calls race with a replacement. Re-fetch on a mismatch.
4. Fetch/cache content privately by account/book/document UUID. Every range/download must carry backend authentication. Avoid loading a 200MB PDF into a single byte array.
5. Open at `resumePage` (one based); convert only at the reader-library boundary if it is zero based.
6. Update local resume immediately; debounce saves; flush on reader close/background where possible. Persist pending progress durably on Android so app death/offline does not erase it.
7. Save exact document/page/version/operationId; retain the same body for uncertain network retries. Only successful new saves advance the server revision.
8. Parse both 409 body shapes; use an explicit conflict decision. Replacements require reopening current metadata/progress, not applying an old page to a new PDF.
9. Show server `pagesRead`, `totalPages`, percentage, completed and lastReadAt. Moving backward preserves furthest progress, but changes resumePage.
10. Offline PDF cache/sync are client responsibilities; no separate backend offline API exists. Clear/isolate caches on account changes.

### Google Drive

1. Check connection → securely initiate browser-bound connect → Google callback → recheck connection.
2. Obtain short-lived Picker configuration/select an accessible PDF.
3. Send selected fileId with durable import UUID; poll until COMPLETED/FAILED.
4. Refresh metadata/progress and read through the app's content API.
5. Handle consent cancellation/revocation/unconfigured integration/rate limits; expose disconnect.
6. Verify native cookie/browser handoff end to end. Backend web OAuth support alone does not establish Android integration completeness.

## 6. Errors, serialization, and concurrency

Application ApiError example:

```json
{"dateTime":"2026-09-30T14:00:00","status":404,"error":"Not Found","message":"Book not found","path":"/api/books/1/document"}
```

`dateTime` is LocalDateTime without an offset. `createdAt`, `lastReadAt`, and SSE `occurredAt` use Instant/ISO timestamps. Do not require every timestamp to have the same format. Nullable fields include description, first-open lastReadAt, absent summaries, and import documentId/message.

| Status | Client meaning |
| --- | --- |
| 400 | Invalid JSON, fields, IDs, cursor, required header/part, page/revision/list |
| 401 | Missing/invalid credentials, expired access token, invalid refresh credentials |
| 403 | Book quota, storage quota, Google permission denial, or denied route |
| 404 | Missing resource or resource inaccessible to this workspace/account |
| 409 | Duplicate data, progress revision conflict, document replacement, reused operation, Drive connection needed |
| 413 | File/multipart size limit |
| 415 | Unsupported request content type or unsupported/unsafe PDF |
| 416 | Invalid/unsatisfiable PDF byte range |
| 500 | Safe generic server error |
| 503 | Temporary storage/parser/notification/Google availability or configuration failure |

Use status plus body schema; do not treat all non-2xx responses as ApiError. Spring Security and framework byte-range errors can have different/empty bodies. For a revision 409, identify ReadingProgress by fields such as documentId/currentPage/version. Range failures can also include Content-Range.

Book/workspace locks serialize quota/document/progress mutations. Progress has a unique account/book record and durable accepted-operation identities. Latest accepted **revision** controls resume; maximum reached page controls percentage/completion. This is not client timestamp last-write-wins. Do not invent a `lastReadAt`, percentage, userId, or workspaceId request field.

## 7. Feature availability and missing endpoints

| Feature | Current source status |
| --- | --- |
| Signup/login/refresh/logout and workspace usage | Implemented backend APIs |
| Create/list/exact-search/numeric book-ID lookup | Implemented backend APIs |
| PDF upload/replacement, metadata, streaming, Range, explicit HEAD | Implemented backend APIs |
| Account-private resume/progress, revision conflicts, retry identities | Implemented backend APIs |
| Batch bookshelf document/progress summaries | Implemented backend API |
| Workspace-scoped book-created live notifications/replay | Implemented SSE; no FCM background push |
| Google OAuth/connection/Picker/queued import/status | Implemented backend; disabled by default; live consent/import not verified in this review |
| Angular upload/PDF reader/Drive calls | Source present in adjacent client; not runtime verified here |
| Android upload/reader/local cache/offline sync | No client source here; audit actual Android project |
| Edit/delete private books or toggle private Book.completed after creation | **No exposed API**; Super Admin can edit/delete public books |
| Numeric-ID GET book metadata | Implemented backend API |
| Delete PDF, list/download/restore historical versions | **No exposed API**, despite retained old versions |
| S3/R2/MinIO provider or signed URL generation | Not implemented |
| Pagination, fuzzy/full-text search, sort API | Not implemented |
| Email verification/password reset/change/profile/account deletion | No exposed API |
| Workspace membership/invites/roles/customer plan upgrade/payment | No exposed API |
| Device registration/FCM push | No exposed API |
| Generic Drive URL download, direct Google-token upload, native OAuth handoff | No exposed API |

Do not assume a UI control means a persistence API exists. In particular, Book.completed can be supplied on create but there is no update route to persist a later toggle. Personal PDF completion is separately implemented.

## 8. Swagger/documentation findings

- `OpenApiConfig` defines title Booker SaaS API/version 2.1.0 and bearerAuth/basicAuth schemes. Protected controller annotations list the two schemes as alternatives; callback is public. Auth operations have a tag but no explicit summaries/error response annotations, so generated Swagger descriptions are less complete than the service behavior documented here.
- Book endpoints and SSE have detailed request/response/error annotations. New PDF/progress operations have summaries and selected error annotations; Google endpoints mostly rely on inferred schemas with limited explicit error documentation.
- Several methods return generic Maps rather than typed response DTOs (tokens are typed, workspace uses explicit Swagger schema overrides). Drive authorizationUrl/connected/Picker field names must be verified against implementation, not assumed from an unconstrained Swagger object.
- Reading progress PUT explicitly documents 409 as `oneOf ReadingProgress, ApiError`; the Android client must implement both shapes.
- Swagger may include GET `/` from HomeController even though SecurityConfig denies it. A visible Swagger operation does not mean the route is permitted.
- `docs/book-notifications.md` contains an old `admin` Basic-auth smoke example and an outdated GET/POST CORS summary. Current shared admin/admin API credentials are unsupported; use a registered account. Current CORS methods include GET/HEAD/POST/PUT/DELETE.
- `README.md` and `docs/book-reading.md` include prior test run counts/recovery notes; these are historical reports, not fresh evidence from this documentation task.
- A live server export was unavailable during the initial inventory; generated OpenAPI was subsequently verified in database-backed MockMvc tests for numeric book lookup and absence of the retired fields/route. Validate deployment-specific settings and schemas on the running deployment before generating Android models automatically. Framework Swagger/health paths are listed separately from business operations.

## 9. Security and deployment requirements for testing

Required backend runtime configuration: PostgreSQL connection and stable base64 `JWT_SECRET` (at least 32 decoded random bytes). Default auth TTLs: JWT_ACCESS_TTL=PT15M, JWT_REFRESH_TTL=P7D. File configuration: BOOK_STORAGE_DIRECTORY, BOOK_MAX_FILE_SIZE=200MB, BOOK_MAX_REQUEST_SIZE=201MB, BOOK_MAX_PAGES=20000, BOOK_WORKSPACE_STORAGE_LIMIT=5GB.

Drive requires GOOGLE_DRIVE_ENABLED=true, GOOGLE_DRIVE_CLIENT_ID, GOOGLE_DRIVE_CLIENT_SECRET, GOOGLE_DRIVE_REDIRECT_URI, GOOGLE_DRIVE_ENCRYPTION_KEY (base64 32 random bytes), GOOGLE_DRIVE_PICKER_API_KEY, GOOGLE_DRIVE_PROJECT_NUMBER. Enable Drive/Picker in Google Cloud, register the exact callback, and configure consent/test users. OAuth secret/encryption key/refresh credentials stay on backend. Picker key is public but restricted.

PDF files need persistent protected storage plus database backups. Multiple LOCAL replicas need shared storage. No public static file serving, automatic inactive-version cleanup, or distributed upload/auth rate limiter exists. Reverse proxies must allow intended upload sizes/timeouts and disable SSE buffering. Native networking is not controlled by browser CORS; browser testing uses configured CORS_ALLOWED_ORIGINS.

Cross-workspace books/documents/progress must return 404 rather than leak data. Range/HEAD are authorized like full reads. Imports are private to the initiating account. OAuth callbacks rely on state + cookie, not a bearer token. PDF validation is implemented but is not an antivirus or process-isolated parser sandbox.

## 10. Review/test checklist for the Android Studio AI

Use this prompt with this file and the Android project:

> Audit the Android project against api.md. For each of the 35 operations report implemented, partial, missing, intentionally unused, or unverified. Cite networking interface/service/model/ViewModel/screen files and line numbers. Check actual URLs/methods/parameters/headers/body fields/JSON mapping/status handling and user-visible features. Distinguish a backend endpoint's existence from Android feature completion. Do not create or assume APIs absent from api.md. Pay special attention to UUID retry identity, rotating refresh tokens, one-based resume pages, server revision conflicts and both 409 bodies, document replacement, authenticated Range downloads, private offline cache, and browser-cookie-bound Drive OAuth. List concrete gaps and prioritize fixes; do not claim success without code/test evidence.

Manual/integration scenarios:

- [ ] Valid signup/login; invalid credentials; duplicate email; correct workspace underscore mapping.
- [ ] Expired access refresh, old refresh reuse rejection, concurrent refresh serialization, logout/local cleanup.
- [ ] Book create validation, duplicate author/title pair, exact single/combined filters, empty list, book quota.
- [ ] Distinct workspaces can use same author/title pair; cross-workspace PDF/progress/HEAD/Range/summary access denied.
- [ ] Upload success/progress/cancel/failure; missing UUID; safe same-key retry; new selection gets new key.
- [ ] Reject empty/non-PDF/oversized/encrypted/unsafe files; failed replacement preserves current file.
- [ ] Metadata correctly decoded; download/inline behavior; full/range/HEAD; invalid range; replaced pinned document.
- [ ] First open resumes at 1; save/reopen page 93 of 144; percentage 64.58; null timestamp handled.
- [ ] Backward navigation preserves pagesRead; final-page completion survives moving backward and stays separate from Book.completed.
- [ ] Reject page 0/out-of-bounds/missing version; retry does not duplicate save; stale/future revisions handled.
- [ ] Exact pending update retained offline; two-device conflict; rotation/app death/background/cache/account switch.
- [ ] Batch summaries <=100; no-document nulls; any unauthorized requested ID fails whole batch.
- [ ] SSE ready/live/replay/deduplication/expiry/backoff/401/503; no assumption of OS push.
- [ ] Drive disabled/not-connected/consent cancel/browser mismatch/expired state/revoked grant.
- [ ] File ID selection (URLs rejected), accepted import polling, completed/failed states, retry/disconnect, import ownership.

Existing backend test sources include TokenAuthenticationTest, WorkspaceIsolationTest, BookReadingIntegrationTest (including Swagger checks), BookDocumentHeadTest, BookDocumentUploadFailureTest, BookReadingMigrationTest, ReadingCompletionTest, LocalFileStorageServiceTest, GoogleDriveIntegrationTest, GoogleDriveGatewayTest, and notification stream/controller/persistence tests. The identity update passed all 74 backend tests, including the event persistence test, against a dedicated disposable database. Use a dedicated test database for integration tests; see README.md. Do not infer live Google success from simulated gateway tests.

## 11. Source map

| Area | Relevant files |
| --- | --- |
| Swagger/security | `src/main/java/com/parvez/android/config/OpenApiConfig.java`, `SecurityConfig.java` |
| Signup/workspace/users | `src/main/java/com/parvez/android/saas/WorkspaceAccounts.java` (also contains WorkspaceController), `WorkspacePrincipal.java` |
| Tokens | `src/main/java/com/parvez/android/auth/AuthController.java`, `TokenService.java` |
| Books | `src/main/java/com/parvez/android/controller/BookController.java`, `service/BookService.java`, `repository/BookRepository.java`, `model/Book.java`, `dto/BookRequest.java`, `dto/BookResponse.java`, `mapper/BookMapper.java` |
| SSE | `src/main/java/com/parvez/android/controller/BookNotificationController.java`, `notification/BookEventStream.java` and related notification classes |
| PDF | `src/main/java/com/parvez/android/document/BookDocumentController.java`, `BookDocumentService.java`, `BookDocument.java`, `BookDocumentRepository.java`, `BookAccess.java`, `PdfInspector.java` |
| Storage | `src/main/java/com/parvez/android/storage/FileStorageService.java`, `LocalFileStorageService.java` |
| Progress | `src/main/java/com/parvez/android/reading/ReadingProgressController.java`, `ReadingProgressService.java`, `ReadingProgress.java` |
| Drive | `src/main/java/com/parvez/android/drive/GoogleDriveController.java`, `GoogleDriveConnectionService.java`, `GoogleDriveImportService.java`, `GoogleDriveGateway.java`, `GoogleDriveSettings.java`, `DriveCredentialCipher.java` |
| Errors | `src/main/java/com/parvez/android/exception/GlobalExceptionHandler.java`, `dto/ApiError.java` |
| Runtime/schema | `src/main/resources/application.properties`, `src/main/resources/db/migration/V1__create_book_table.sql` through V9, `compose.yaml`, `Dockerfile` |
| Existing documentation | `README.md`, `docs/book-reading.md`, `docs/book-notifications.md` |
| Adjacent Angular integration | `../booker-ui/src/app/reading/document-api.service.ts`, `reading-state.service.ts`, `session.service.ts`, `google-picker.service.ts`, `pdf-reader.ts`, `../booker-ui/src/app/app.routes.ts` |

The author/title identity update changes book DTOs, detail lookup, uniqueness and event snapshots. Other PDF/progress/Drive contracts remain the same. See README.md for the V9 migration preflight and coordinated client deployment.


## 12. Public Library — steps 24–35

The complete method-by-method requests, response examples, errors, authorization, workspace progress, raw streaming upload, bootstrap and cleanup contract for steps 24–35 is in [docs/public-library.md](docs/public-library.md). Existing steps 1–23 are unchanged. Every public-library route requires authentication; the word public does not mean anonymous download.

Use SUPER_ADMIN_EMAIL and SUPER_ADMIN_PASSWORD for initial system-owner provisioning, then the existing login/refresh/logout flow. No separate admin authentication exists. Public management (create/update/delete/PDF upload) requires that role. Ordinary workspace users can read public books/PDFs and read/update only their current workspace's progress. Super Admin is standalone, has no workspace, and cannot select customer private data or workspace progress.

Public metadata uses the existing BookRequest/BookResponse. Exact author/title pairs are globally unique for public books, independently of private pairs. Public creation has no book quota and emits no private SSE event. Public PDF POST streams application/pdf with fileName query plus Idempotency-Key UUID; there is no private PDF size/page/storage quota on this route. Private multipart upload and its configured limits are unchanged.

Public progress reuses the existing Update/ReadingProgress schemas, math and two 409 shapes, scoped to workspace rather than individual email. Batch summaries reuse Summary. Deletion cascades public metadata/progress and durably queues all document versions for storage cleanup. Public IDs cannot be resolved by private routes, and private IDs cannot be resolved by public routes.

Frontend integration is outside this backend feature; no Android/Angular source was modified.
