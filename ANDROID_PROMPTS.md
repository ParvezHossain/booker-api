# Android client implementation specifications

**Status: proposed client work.** This repository contains the Spring Boot backend,
not an Android app. Kotlin/Gradle sources and actual SDK/UI/network versions must
be inspected in the client checkout before changes. Reuse its established networking,
DI, state, navigation and testing patterns; MVVM/Compose/Coroutines/Retrofit are
possible client choices, not verified dependencies in this backend repository.

[API.md](API.md) is the authoritative HTTP reference. Download `/v3/api-docs` from
the target deployment when generating a client; this document does not maintain a
second hand-authored OpenAPI snapshot. Architecture/configuration and provider
constraints are in [PDF reading](docs/book-reading.md) and
[operations](docs/operations.md). There are 49 implemented business operations.

## Contract invariants

- Books use numeric int64 IDs; workspace/document/import/operation IDs are UUIDs.
  Account identity is normalized email and comes from authentication, not selectors.
- Private books belong to a workspace; progress/Drive imports belong to an account.
  Public books are global to authenticated readers, with workspace-shared progress.
- Book metadata is `title`, `author`, `publishedDate`, nullable `description`, manual
  `completed`. Private metadata has no update/delete route. Public management requires
  Super Admin; that account cannot access private/workspace progress.
- Prefer Bearer sessions with serialized single-use refresh. Signup issues no sessions and new owners require email activation; 204 activation is followed by login.
  Password change/reset invalidates earlier account sessions. Basic remains supported.
- Stream uploads/downloads with bounded buffers and Long byte counters. Preserve
  exact file bytes and UUID Idempotency-Key on uncertain retry; upload cancellation
  can still commit on the server. Replays may return inactive metadata; refetch active.
- PUT progress requires active documentId, one-based currentPage, explicit nonnegative
  version and operationId. First GET is page 0/resume 1/version 0 without a row.
- Maximum page drives percentage/completion; resume supports backward reading. A stale
  version can merge maximum while preserving server resume/time and returns 409
  ReadingProgress. Replacement/reused operation returns 409 ApiError. Never blindly
  resubmit against a newer revision or trust device timestamps.
- Summary requests contain 1–100 IDs; map by bookId. Missing PDF is null document/progress,
  unlike a failed summary request. No API exposes file-storage quota usage.
- SSE emits ready, book.created (schemaVersion 2) and public-book-request.reviewed
  (schemaVersion 1), plus heartbeat comments. No PDF/progress/import completion push exists.
- Google Picker is browser-based. Retrofit and browser cookie stores differ; there is
  no implemented native OAuth handoff or backend native-token exchange endpoint.
- ApiError has dateTime/status/error/message/path. Its local timestamp has no offset;
  progress/document timestamps are Instants. Security/proxy/HEAD/Range errors need
  defensive handling; never render raw provider bodies or credentials.

For emulator development, `http://10.0.2.2:8080` addresses the host API. Release
requires HTTPS. A physical device needs a reachable configured host. A Retrofit
base URL ending `/api/` uses relative annotations; health/OpenAPI are origin-root
paths. Scope all authorization to the trusted backend origin.

## Ordered implementation specifications

Each item requires tests of its observable behavior and client build verification.
Inspect existing implementation rather than recreating preceding work. Required
backend/browser changes must be documented independently before native integration.

### 1 — Inspect the real Android project

**Objective:** Inspect the real Android project.

**Requirements and acceptance tests:**

Inspect the whole Android project and backend contract before modifying code. Locate the application package, contribution rules, Gradle/version catalog, SDK levels, manifest/network security, dependencies, tests, DI, navigation, serializer, network/repository layer, SSE/cursor handling, ViewModels, library/details/create UI and notifications. Verify actual class/file names rather than assuming a supplied template. Check whether any PDF/local database/token persistence already exists.
Compare the actual implementation with this specification and API.md. Produce architecture summary, exact file map, supported/backend-missing feature matrix, proposed local data model, API mapping, private-file storage design, and ordered list of likely changed files. Confirm Kotlin Long for bookId/fileSize/version and nullable response fields. Confirm authentication and every Android request's resolved URL.
If source is unavailable, say precisely what is missing; do not generate a replacement app. Acceptance: client architecture, contracts, dependencies and a reviewed delivery plan are recorded before implementation.

### 2 — Network contracts, DTOs, and errors

**Objective:** Network contracts, DTOs, and errors.

**Requirements and acceptance tests:**

After the architecture review, extend the current DTO/network/repository structure. Add Tokens/Login/Refresh, Document, ReadingProgress, ProgressUpdate, ReadingSummary, DriveConnection/Picker/ImportStatus models using the existing JSON converter. Use the current Book DTO without the retired identifier; detail lookup is GET /api/books/{bookId}. Other response shapes remain unchanged. Preserve explicit version=0 serialization and nullable description/lastReadAt/document/progress/import documentId/message. Add Retrofit methods for the exact existing routes in API.md, including multipart Idempotency-Key, @Streaming content ResponseBody, HEAD, batched bookIds, and progress Response<ReadingProgress> so 409 error bodies can be inspected.
Separate public auth client from authenticated API/SSE/download clients. Normalize base URL once. Route errors into typed outcomes: ApiError, progress conflict, unauthorized, retryable failure, and safe unknown failure; body decoding must use the configured serializer, not substring matching. Consume/close response/error bodies. Do not build screens or add pretend native OAuth routes.
Tests: wire JSON field names/nulls/Long values, explicit version zero, correct URLs/no double api, multipart file part/header, binary streaming, empty 204, and both 409 response shapes. Provide fixtures matching API.md.

### 3 — JWT session lifecycle and safe refresh

**Objective:** JWT session lifecycle and safe refresh.

**Requirements and acceptance tests:**

For a client using Basic-only sign-in, migrate the workflow to POST auth/login, then GET workspace and books. Signup 201 now opens email activation when activationRequired=true; consume the emailed token through POST auth/activate before login. Add generic POST auth/resend-activation, configurable UTC expiry display, explicit confirmation and login-403 routing. Preserve pending-account state without storing passwords/tokens. See the complete [Gemini activation handoff](docs/android-email-activation-gemini.md). Add a session coordinator shared by Retrofit, SSE and downloads. Refresh tokens rotate: persist the replacement pair atomically before retrying dependent work. Enforce single-flight refresh across concurrent requests, recognize when another request already replaced the token, cap authentication retries, and use a refresh client that cannot recursively authenticate or deadlock the same executor. Distinguish auth rejection from transient refresh network/503 errors.
Attach Bearer only to the configured trusted API origin. No password storage, credential logging, query tokens or authorization forwarded to arbitrary redirect hosts. In-memory session remains the default. If adding explicit Remember me, use a reviewed Android Keystore-backed design compatible with the actual SDK and exclude tokens from backup; do not blindly introduce deprecated persistence APIs. Without remembered login, background jobs defer until reauthentication after process death. A lost refresh response may consume the old token; handle re-login instead of an infinite refresh loop.
On logout, attempt auth/logout with the latest refresh token, then clear local session regardless of network result. Explain server access JWTs remain valid until expiry. Serialize logout against refresh and block late old-session callbacks using a session generation. Cancel SSE/network/jobs and hide account-scoped UI immediately. Keep pending offline progress inaccessible to other accounts; offer explicit discard on logout rather than silently losing unsynced reading. Restore it only after verifying the same account/workspace. Do not fall back silently to Basic after a Bearer failure.
Tests: pending signup/activation/resend/expiry/replay, activation-required login 403, ordinary login, invalid credentials, ten simultaneous 401s causing one refresh, token rotation, transient failure, consumed refresh, logout during refresh, and account switch during a delayed response.

### 4 — Preserve catalogue, workspace, SSE, and notifications

**Objective:** Preserve catalogue, workspace, SSE, and notifications.

**Requirements and acceptance tests:**

Integrate the session coordinator with the existing catalogue and SSE manager. Preserve exact author/title query semantics (omit blank filters), numeric book-ID lookup, book creation and quota messages. publishedDate is required. Book.completed remains creation-time metadata; no update/delete controls. Keep local text search labeled/implemented consistently with existing UI, never claim backend full-text search.
SSE remains GET books/events with Accept text/event-stream and Last-Event-ID. Persist opaque cursor strings only after successful event processing; ready has {} with a cursor, book.created has the BookEvent envelope. Scope cursors/dedupe by API environment+normalized account+workspace; no token-derived key. Upsert duplicate REST/SSE creations once. Handle invalid cursor by a catalogue resync and a deliberate fresh subscription/replay policy; do not create a reconnect gap that loses new books. Retain backoff/jitter, five-minute server close, foreground lifecycle, one stream per session and token refresh on 401. Cancel stale collectors on logout. Do not reconnect indefinitely on permanent auth failure.
Keep native notifications and permission behavior, avoid duplicate notifications on replay/own REST creation according to existing UX. Do not promise delivery while the app is killed: backend has no FCM integration. No document/progress SSE events exist.
Tests: ready, replay, duplicates, bad/future cursor, 401 refresh, 503, foreground/background, account changes and notification permission denied.

### 5 — Durable local reading state and account isolation

**Objective:** Durable local reading state and account isolation.

**Requirements and acceptance tests:**

Implement a focused local store using the project's existing persistence approach. If none can support atomic durable operations, justify a small suitable addition before choosing it; do not add a whole architectural framework. Key state by API origin/environment, normalized verified account email, workspace UUID, book Long and document UUID. Store acknowledged server progress separately from latest local resume intent, local maximum intent, immutable in-flight request body/operation UUID, and next unsent intent. Store document metadata and a small authorized catalogue cache sufficient to reopen an offline book after process recreation for the same account.
Persist page changes promptly and atomically before relying on lifecycle network callbacks. Allow one in-flight mutation per account/book. A newer page must not mutate an uncertain operation's body or revision. Track enough state to avoid erasing newer local navigation when an older response arrives. If local furthest page exceeds the latest local resume page, preserve both; the API has no writable pagesRead field, so sync the maximum then resume as separate ordered operations when necessary, handling conflicts at each step.
Define session lock versus explicit account-data deletion, cached-file quota/eviction, exclusion from backup, disk failure, schema migration, and process recovery. Never store passwords or PDF binary in the local progress database. Keep an offline library locked until the user's established local-session policy permits access; no cross-account fallbacks. No background sync without usable credentials.
Tests: atomic recovery, process recreation, two accounts in one workspace, two environments with same bookId, latest local intent surviving old responses, max 93 followed by resume 20 offline, and disk write failure.

### 6 — Select and upload/replace a PDF

**Objective:** Select and upload/replace a PDF.

**Requirements and acceptance tests:**

Add device PDF selection using the existing Compose Activity Result pattern and ACTION_OPEN_DOCUMENT/application/pdf. Use ContentResolver; never infer a filesystem path from a content URI. Take persistable read permission when available and handle revoked/moved/deleted/cloud-provider documents. Query display name and nullable size safely. No broad storage permission. Validate advisory filename/MIME/known size; report server limits/errors without claiming 200MB is immutable.
Create a durable upload operation before sending. Stream multipart file from a reproducible source with bounded buffers and Long counters; show throttled progress or indeterminate progress for unknown length. For provider content that can change or cannot be reopened reliably, stage a bounded app-private snapshot to ensure identical retry bytes and clean it up when safe. Do not use readBytes(), ByteArray or Base64 for full PDFs. Do not emit fake network progress while computing content length. UI distinguishes transferring from server validation after 100% bytes sent.
Reuse the same UUID Idempotency-Key and identical file for uncertain network retries; new file or intentional replacement gets a new UUID. Handle cancellation without asserting server rollback. Prevent duplicate taps. On 201 fetch active document, progress and affected summary (replayed document might be inactive); keep the old document usable on upload failure. Explain replacement resets personal active-document progress and retained versions consume quota. Only refresh book quota when relevant; file storage quota is a different limit.
Tests: success, exact retry after lost response, immutable source, new-file/new-key, MIME/extension/empty/413/415/403/503, unknown size, cancellation, provider permission loss, process recreation and account switch. No automatic infinite retries or new key per retry.

### 7 — Secure PDF download and offline file cache

**Objective:** Secure PDF download and offline file cache.

**Requirements and acceptance tests:**

Implement a private document download/cache repository. Fetch active metadata and personal progress, ensure matching documentIds, then GET books/{bookId}/document/content?documentId=<UUID>. Download with @Streaming/OkHttp to an app-private .part file using bounded buffers off the main thread. Never ResponseBody.bytes(). Verify expected size and SHA-256 against metadata before atomic promotion to a complete readable file. Names must be generated/cache keys, not server filenames used as paths. Implement disk space checks, cancel/progress, eviction without deleting an open file, and cleanup of abandoned partial files.
Cache identity includes environment/account/workspace/book/document. PDF HTTP responses are no-store: do not use a shared HTTP cache; explicitly downloaded offline files are a separate private app feature with a clear user policy and removal controls. Do not export tokens or PDF URLs to external viewers. Revalidate active document online; while offline label cached version and preserve local page. Upon replacement isolate old queued progress; never transplant it onto the new PDF.
Support resumable byte downloads only with tested validation: request Range bytes=<partialLength>-, pin documentId, inspect 206 Content-Range and total/ETag before append; 200 means truncate/restart, not append; handle 416 by reconciling expected size/hash or restarting. Always verify final checksum. A 409 requires active metadata refresh and user-visible replacement state. HEAD is optional and bodyless; lengths use Long. On 401 use the shared coordinator; 404 removes access from online UI; 503 retries without corrupting the cached complete file. Authorization headers stay on the trusted backend origin.
Tests: complete/partial/truncated/corrupt response, range ignored with 200, wrong Content-Range, 416, replacement during download, low disk, logout mid-download, missing bytes and offline reopen.

### 8 — Compose PDF reader and saved-page restoration

**Objective:** Compose PDF reader and saved-page restoration.

**Requirements and acceptance tests:**

Reuse any adequate existing PDF reader. If none exists, implement a focused renderer adapter using platform PdfRenderer when supported by the project's min SDK; inspect current official documentation before choosing an alternative dependency. PdfRenderer needs a seekable file descriptor, so open only a verified complete local PDF from item 7. This initial approach downloads before reading; do not claim instant remote range rendering. Range support can resume downloads independently.
Add a reader destination following existing navigation, ReaderViewModel/state and focused renderer lifecycle. API pages are 1-based; renderer indices are 0-based. Restore same-document local unsynced page when present, otherwise server resumePage, otherwise 1 for first-open. Do not reset to 1 due to a failed progress fetch. Avoid saving a default page before restoration completes; mark actual displayed/user-navigation state deliberately. SavedStateHandle alone is not durable pending progress.
Render bounded visible pages/bitmaps off the main thread; serialize renderer access where required, close page handles before renderer, close descriptors, cancel obsolete renders and cap bitmap dimensions during zoom. Provide previous/next/page entry, current/total, zoom/pan, loading/retry, offline and unsynced states. Persist the local page immediately through item 5. Rotation/background/back must retain page; do not pass bitmap/file streams in navigation arguments. Validate renderer page count against document metadata and handle mismatch/corruption safely. Keep PDF personal completion distinct from Book.completed.
Test 144-page fixture restoration at 93 (index 92), first-open at 1, backward reading, rotation/process recreation, rapid navigation, very large pages/files, corrupt/encrypted rejection, descriptor cleanup and accessibility labels/large fonts. Do not claim selectable text/search if the chosen renderer cannot provide it.

### 9 — Debounced progress sync, offline recovery and conflicts

**Objective:** Debounced progress sync, offline recovery and conflicts.

**Requirements and acceptance tests:**

Implement the deterministic outbox from item 5 with exact backend CAS semantics. Debounce page-change sync around 1.2 seconds, coalesce unsent intentions, attempt flush on reader exit/background and reconnect, and persist before network attempts. Lifecycle flush is best effort; process-death reliability comes from durable local state. Integrate existing scheduler or justified WorkManager for deferrable connectivity work, not an always-on service. Workers must verify current account/session and serialize with foreground sync; do not send after logout.
Each PUT body is immutable {documentId,currentPage,version,operationId}; use version 0 explicitly for first save. Network uncertainty keeps the exact body/key for retry. On 200 consume current returned progress while preserving any newer local intent; do not assume response equals the original page. Send later intent with returned revision and a new UUID. Persisted percent/completed are server authoritative; pending local position is labeled unsynced, not silently presented as acknowledged percentage. Preserve offline maximum and resume separately; if 93 then 20 was read offline, sync maximum intent before final resume as needed.
On 409 parse ReadingProgress versus ApiError structurally. A stale-revision ReadingProgress may already merge maximum; store the returned revision/summary and pause automatic resume overwrites. Show server/local page choice. Use server choice to settle local intent; local choice sends a new operation using returned revision. If another conflict happens, handle it again without an infinite loop. Replaced-document ApiError triggers metadata/progress refresh and explicit reopen, never remap old pending pages. Operation-reuse errors are integrity faults, not generic retry requests. Future revision is also a conflict. Server timestamps, not device clock, decide lastReadAt.
Tests: 93/144=64.58, first-open version0, exact retries, lost 200 response followed by another device update, later local page during an in-flight request, stale merge preserving resume/time, future revision, both 409 shapes, operation reuse, document replacement, offline 93->20, two device conflict choice, two workers, expired session and app kill. Reaching final page then going backward stays completed; rounded 100% alone does not mean completion.

### 10 — Book list summaries and reading UX

**Objective:** Book list summaries and reading UX.

**Requirements and acceptance tests:**

Add document/progress summaries alongside the current Book DTO using batched calls; avoid per-card requests. Deduplicate book IDs, chunk into at most 100, skip empty calls, limit concurrency and map results by bookId. Preserve catalogue even when summary fetching fails; unknown/error is not 'no PDF'. Refresh after upload/import/save, foreground resume and explicit refresh because SSE has no reading events. Respect filters and account/session generation when responses arrive.
For document=null show Upload PDF and available Drive entry. With a document show Continue from resumePage/Open, current/total, server percentage, optional pagesRead and formatted lastReadAt, plus a collapsed upload/replace action. Keep manual metadata completion visually separate. For never opened show Not started (resume 1), and for pending local state show page and Unsynced. Use backend numbers; formatting 64.58 to 64.6 for display is fine, recomputing persisted progress from currentPage is not. Derive remaining book capacity from book_limit/books_used; do not invent document storage usage fields.
Tests: mixed missing/present documents, summary errors, >100 books, chunk isolation/error handling, no N+1 fetch pattern, backward reading with high maximum, replacement and accessibility/theme consistency.

### 11 — Google Drive integration boundary and usable browser flow

**Objective:** Google Drive integration boundary and usable browser flow.

**Requirements and acceptance tests:**

Inspect GoogleDriveController, ConnectionService, the deployed Angular Google Picker flow, and current official Google OAuth restrictions before writing Android OAuth code. The existing POST connect sets booker_drive_binding as HttpOnly, SameSite=Lax, callback-path cookie in the requesting browser. Retrofit's cookie jar is not the Custom Tab's cookie jar. Opening authorizationUrl after a Retrofit call will lose the required browser binding. A native Google access token is not accepted by an existing backend endpoint. Picker is a web API, not a native file-list endpoint. Do not embed Google login in a WebView, weaken state/cookie checks, put Booker tokens in browser URLs or ship client secrets.
Implement the route that works with existing infrastructure: a configured HTTPS Booker web application link opened in an external browser/Custom Tab. The user signs into that web app independently with the same Booker account, connects Drive and selects/imports a PDF using the existing same-origin browser flow. Android should explain this handoff and refresh connection/document/progress/summaries on return. Inspect the deployed Angular route before constructing a book-specific URL; never invent one or rely on an unverified app callback. If the web app has no compatible deployed mobile-browser flow, show a precise unavailable state instead of a fake connected success. Test real consent/Picker in deployment before claiming it works.
Also let the normal Android document picker expose a Google Drive DocumentsProvider when installed: that streams a selected URI to the ordinary upload API, sourceType=UPLOAD. Label it correctly; it is not the backend GOOGLE_DRIVE OAuth import integration and is not guaranteed to be installed.
Add authenticated connection status/disconnect and backend ImportStatus repository support. Do not expose manual arbitrary URL imports. For a fileId obtained from a verified authorized selector flow, POST the existing import endpoint with one durable UUID and persist/poll its Location using trusted relative URLs. No native selector is implied by these methods. Existing statuses are only PENDING/RUNNING/COMPLETED/FAILED. COMPLETED refreshes active metadata/progress; FAILED displays safe message. A failed job needs explicit new operation to restart; same key retrieves the old status. Poll with bounded backoff, pause on background, restore known job after recreation. Backend retries transient Google failure itself (30s/60s, ordinary maximum three attempts); do not create duplicate jobs while pending. Disconnect preserves PDFs, cancels pending jobs as FAILED, but running jobs may finish. Status is private to initiating account.
Tests: connection false/disabled, browser return without success, different browser account, disconnect, pending/running/completed/failed, repeat key, process recreation, hidden other-account job, safe Location, revoked Google grant, cancelled consent and missing DocumentsProvider. Clearly report whether full browser flow is deployed and tested.

### 12 — Optional seamless native Drive handoff: backend prerequisite, not an existing API

**Objective:** Optional seamless native Drive handoff: backend prerequisite, not an existing API.

**Requirements and acceptance tests:**

This optional work requires a separately scoped backend/browser/native design. Produce the backend/browser/native handoff design first, using the repository contribution and API compatibility rules. Do not add Retrofit stubs for nonexistent routes or pretend an Android-only change can bypass browser binding.
Design a short-lived single-use authenticated handoff bound to Booker account, book, allowed return URI and app proof (for example an app-held verifier/challenge), redeemed by the browser to establish its own binding. Review login-CSRF/account confusion: a leaked handoff URL alone must not silently link an attacker's Booker account to a victim's Drive. Prefer an explicit authenticated same-account browser confirmation or equivalent reviewed binding. Keep browser sessions/Picker scoped to the handoff; do not place long-lived API/Google tokens in URLs. Reuse backend state expiry/PKCE/encrypted Google credentials/selected-file permissions and authorize the chosen book at every transition. Restrict redirects, suppress referrer/token logging, make replay/expiry cancellation safe, and return only operation identifiers via verified HTTPS Android App Links. Android re-reads authorized result from backend; never trust callback payload as successful import.
Before implementation, write exact proposed OpenAPI request/response/error contracts, schema/migration changes if needed, security threat cases, browser pages, required domain/assetlinks/OAuth configuration, and tests for account mismatch, interception, replay, forged return links and expiry. Mark every new route PROPOSED until implemented and tested. Keep these out of API.md's current contract. Review the design before implementation. After implementation update live OpenAPI and Android contract tests, then integrate the verified native flow. If backend/browser access is unavailable, report this dependency and leave the working browser/device-upload paths intact.

### 13 — Security, resource limits, and end-to-end tests

**Objective:** Security, resource limits, and end-to-end tests.

**Requirements and acceptance tests:**

Review all feature diffs for credential exposure, backup leaks, cross-account local files/jobs/cursors, stale callbacks, untrusted redirects/filenames, publicly readable PDFs, URI permission misuse, and oversized buffers/bitmaps. Confirm release HTTPS; allow emulator cleartext only in debug configuration. Check auth/refresh/error logs and crash reports redact credentials and sensitive file data. No storage keys or cloud secrets in APK. No arbitrary URL download or Google OAuth WebView.
Run behavioral unit/MockWebServer tests and instrumented/Compose tests supported by the real project. Reuse existing test tools; report exact commands/results/skips. Minimum end-to-end matrix: signup/login/refresh/logout; quota/duplicate author/title pair; SSE replay; upload and lost-response retry; range download and integrity check; resume page93; offline/process kill/reconnect; backward/final-page completion; multi-device conflict choice; document replacement; same-workspace different-user progress; Workspace A attempts Workspace B document/HEAD/range/progress/import status; Drive disabled/revoked/failed/browser success. Use only disposable test accounts/databases/files.
Profile a large permitted PDF on a low-memory emulator/device: bounded upload/download memory, Long byte counters, bitmap cap, cancellation and disk pressure. Test reader opened offline after restart and queued progress surviving until same-account login. Do not claim a cached app can revoke access instantly while offline; document that limitation. Do not claim background SSE is push.
Backend tests require dedicated PostgreSQL/JWT configuration; do not point them at production or rerun destructive setup on user data. If Android SDK/emulator/live Google deployment is unavailable, report exactly which verifications remain manual and provide reproducible steps.

### 14 — Release documentation and final acceptance

**Objective:** Release documentation and final acceptance.

**Requirements and acceptance tests:**

Update Android README with SDK/build/run instructions, debug emulator and physical-device base URLs, HTTPS release configuration, signup/login/refresh, upload/replace semantics, private offline cache/removal, per-user resume, conflict choices, background sync limits, and browser versus native Drive status. Export/capture the deployed OpenAPI contract and record its backend revision; retain source-derived supplements for conflict/SSE semantics. Document every new dependency and migration in Android, plus token/backup/account-data policy.
List completed features versus externally blocked native OAuth/deployment items. Include actual changed files, tests run with results/skips, observed large-file behavior, known limitations, and deployment configuration maintainers. Run the repository's appropriate lint/unit/build/instrumented checks; do not fabricate task names or results. Compare every promised UI action with an existing/tested endpoint. No release claim while native Drive is represented as implemented but only a stub exists. Preserve existing catalogue/auth/signup/SSE behavior throughout.

## Public library, requests and password integration

Use the existing public APIs with explicit private/public reader context. Public
progress is shared by a workspace and labeled accordingly; Super Admin previews
PDFs without workspace progress. Ordinary users cannot manage the global catalogue.
Submit requests as `{title, authorName}` and display workspace request history.
Handle the shared workspace limit of ten successful requests per UTC calendar month.
On 429, show the backend message/reset delay from Retry-After; avoid automatic
submission retries until the delay expires. Reviews do not restore allowance and
local history counts must not override backend enforcement.
The backend queues Super Admin notification email through a PostgreSQL outbox and RabbitMQ on submission; success does not
confirm delivery. Clients must not send emails or provide administrator recipients.
Admin acceptance uses multipart `metadata` and `file`; title/author come from the
request. Ambiguous review responses require status reconciliation before a retry;
acceptance does not have the upload idempotency contract.

Add authenticated change-password and public forgot/reset flows using API.md.
Handle generic 202 without account disclosure; eligible requests persist email then
return before SMTP/RabbitMQ. Background retries do not extend expiry, and 202 does
not prove delivery. Missing SMTP/sender/queued-token encryption key returns 503. Support pasting the 43-character
emailed token when PASSWORD_RESET_URL is blank; an HTTPS app link is optional.
Submit {token,newPassword}, then clear old sessions after successful change/reset.
Handle reset 429 as a per-account successful-reset quota (default three per UTC
calendar month, configurable on the backend); show the message and Retry-After
seconds and avoid automatic retries. After renewal a fresh email may be required.
Forgot-password stays generic 202 without email at exhaustion; 202 does not prove
delivery or remaining allowance. Authenticated change-password does not count.
Do not log links/tokens or claim the app-link handler exists until implemented.

The backend queues a security confirmation to the affected account after a successful
change/reset and audits connection IP plus optional User-Agent/browser/device. A 204
does not confirm SMTP delivery. Use normal client User-Agent behavior (an accurate
app/platform agent may be supplied by Android); do not add recipient/workspace/IP
selectors or send email from the client. Client metadata is untrusted and may be unknown.

Test wrong/expired/replayed reset, monthly 429, generic quota-exhausted 202,
session cleanup and admin/workspace access boundaries.

## Account security history integration

Use API.md section 7.2 for successful-login and password-change/reset history.
Workspace UI reads `/api/workspace/login-history` and
`/api/workspace/password-change-history`; Super Admin UI uses `/api/admin/login-history`
and `/api/admin/password-change-history`, optionally filtered by workspaceId.
Respect 403 without changing local identity or attempting a workspace selector on
an own-history route. Each response is {items,nextCursor}, with limit 1–100 (default
50), UTC timestamps and nullable workspace/IP/User-Agent fields. Use nextCursor
with the same history/filter, refresh from page one for new arrivals, and reset the
cursor when changing filters. These pages expose no totals or aggregate metrics.
Treat device/browser as client-reported, render raw User-Agent as text, avoid logs
and persistent caches of activity metadata, and show empty/unknown states. Ordinary
Basic/Bearer requests and refreshes are not new logins. Test workspace/admin scope,
secret-free rendering, pagination, invalid cursors, 400/401/403 and session invalidation.
These are client requirements; this repository implements the backend only.

## Release acceptance

- Existing signup/catalogue/search/numeric lookup/quota/SSE behavior remains covered.
- Transport DTOs, nullable fields, raw/multipart distinctions and both 409 shapes match API.md.
- Upload retry identity and immutable bytes survive lost responses/process recreation.
- Private cache is bounded, verified and scoped to environment/account/book/document.
- Page restoration at 93/144, backward/final-page reading, rotation/process death and
  offline reconnect preserve resume/maximum and pending operations.
- Two devices and account switches cannot silently overwrite progress or leak files/events.
- Public workspace progress and Super Admin preview/management respect actual permissions.
- Device-provider upload, browser Drive import and proposed native handoff are labeled distinctly.
- Large PDFs are exercised on a representative low-memory device with bounded buffers/bitmaps.
- Client build/unit/instrumentation commands and real outcomes are recorded; live Google,
  SDK/emulator or deployment checks unavailable to the release are listed explicitly.

## Backend configuration boundary

Clients need only public API/web origins and deployment-agreed deep links. Database,
JWT signing, OAuth client secret, Drive encryption and SMTP credentials stay backend-only.
Picker runtime config contains a short-lived access token and public restricted key;
keep it in memory. See [operations](docs/operations.md) for backend requirements.
