# Booker Angular implementation instructions

## Purpose and source of truth

These rules apply to the separately maintained Booker Angular client. Angular 22+
and compatible Node.js 24 are the specified target; verify the actual client stack
and official compatibility matrix before a toolchain change. These are instructions for future implementation; this file does not claim that any frontend feature exists.

Read this file and `ANGULAR_PROMPTS.md` before working. Read the relevant endpoint sections of `API.md` before each feature. Copy `API.md` into the UI repository too, or configure an accessible reference path. Do not rely on a link to a backend folder that is unavailable.

`API.md` is the HTTP contract. Proposed backlog items are not implemented endpoints. If documentation and a live response disagree, report the concrete discrepancy; do not silently invent a contract or modify the backend.

Keep these client rules accessible from the client repository contribution guide.
Preserve its existing instructions and backend contract reference.

## Working process

1. Inspect the UI repository, instructions, package versions, routes, components, services, styling, tests and configuration before editing.
2. Keep changes within the agreed work item. Complete an architecture/access review before foundational changes; avoid unrelated rewrites.
3. Before changing an existing class/service/component, explain the intended change and its purpose. Preserve unrelated work and existing conventions.
4. Reuse an existing app. Scaffold only if the folder has no application. Do not rewrite the architecture, add a backend, or modify backend/database files.
5. Maintain the client implementation checklist with actual behavior, checks and limitations; mocked integration is not live provider verification.
6. Add meaningful behavioral tests and run client verification. Build on existing implementation rather than recreating it.

## Toolchain and architecture

- Use a stable Angular release >=22 compatible with Node 24; verify official compatibility and npm package engines before installation. A Node major version alone does not establish compatibility. Check the exact release against: [official version matrix](https://angular.dev/reference/versions) and [next documentation matrix](https://next.angular.dev/reference/versions).
- Align Angular core, CLI, compiler and any Material/CDK versions. Use CLI-compatible TypeScript/RxJS versions; do not force peer dependencies. Pin a supported Node 24 patch in runtime configuration and CI; commit the package lock.
- Prefer standalone components, lazy feature routes, strict TypeScript/templates, typed reactive forms, functional HTTP interceptors/guards, and Angular signals for local UI state. Use RxJS for HTTP and async orchestration. Follow the installed release's supported APIs and generated defaults, including its change-detection setup.
- Keep HTTP access in typed services and reading synchronization in a separate coordinator. Share document/reader primitives while keeping private/public access and progress scopes explicit.
- Suggested feature boundaries, adapted to the existing repository: `core` (auth, config, errors), `shared` (UI), `auth`, `workspace`, `private-books`, `public-library`, `reader`, `drive`, `requests`, and `admin`.
- Reuse the design system. For a new app use a small consistent accessible design; add Material only if justified and compatible. Avoid unnecessary state frameworks and PDF wrappers. Select a maintained PDF library after verifying Angular compatibility and authentication/range support.
- Default to a browser SPA; SSR, PWA, subscriptions, analytics and billing are separate scope.

## Contract invariants

- Base API is `/api` with no version segment; local backend defaults to `http://localhost:8080`. Use injectable public runtime configuration or environment configuration. No secrets in frontend configuration.
- Success responses are direct objects or arrays, not `{data: ...}`. Lists have no server pagination or sort parameters. Exact author/title filters are not substring search. Client-side sorting/pagination must be labeled as local.
- Books use numeric int64 `id`, `title`, `author`, `publishedDate`, nullable `description`, and manual `completed`. Validate numeric IDs as safe integers; report a contract gap if the backend exceeds JavaScript precision rather than silently rounding. UUIDs are strings. SSE cursors remain opaque strings.
- `publishedDate` is a nonblank string up to 20 characters, not necessarily a date. Title/author limits are 255; description limit is 5000. Signup email/password/workspace constraints come from section 6.
- Workspace response uses `book_limit` and `books_used`. Workspace/user identity is derived by the server; never send a workspace selector header or field.
- Private book API supports list, detail and create only. Do not invent private update/delete, metadata completion-toggle, version history, signed URL, user profile, role assignment, billing or entitlement APIs.
- Public catalogue and PDFs require authentication. OWNER has workspace features; SUPER_ADMIN manages public books and reviews but has no workspace, private books or reading-progress access.
- Login returns tokens, not a profile or role. The current JWT source has no role claim; `API.md` does not document one. Do not infer SUPER_ADMIN from JWT decoding, an email convention, or a fabricated `/me`. Design explicit user/admin entry flows with server-authoritative 403 handling; any role-discovery endpoint is a documented backend gap.
- Private reading progress is account-specific. Public progress is workspace-shared, not per account. Never mix their caches or endpoints.

## Authentication and browser security

- Use access-token Bearer authentication. Do not store account passwords or use legacy Basic for this UI.
- Restrict bearer injection to the configured backend origin and protected API paths. Do not send Booker tokens to Google, assets, unrelated hosts, or public auth endpoints. Invalid Authorization can break otherwise public endpoints.
- Implement single-flight refresh with atomic access/refresh replacement; concurrent use of a rotating old refresh token produces 401. Retry an original protected request at most once. Refresh/login failures cannot trigger refresh recursion.
- Coordinate refresh across tabs if credentials are shared. Never blindly retry a rotation after an ambiguous network failure. Explain the chosen token storage policy: in-memory tokens mean login after reload; optional browser persistence increases exposure to XSS. The backend has no HttpOnly application refresh-cookie endpoint. Do not claim JavaScript storage is secret or implement a fictional cookie contract.
- Logout posts `{refreshToken}` and clears local credentials, sensitive state, queues and subscriptions even when the network fails; remote revocation cannot then be guaranteed. Password changes/reset invalidate existing sessions.
- Password recovery has a backend-configured successful-reset allowance per account per UTC calendar month (default three). On reset 429, display ApiError and Retry-After without automatic retries or clearing sessions. Eligible forgot-password requests commit encrypted email receipts and return before SMTP/RabbitMQ; delivery retries retain the original expiry. Forgot-password stays generic 202 without mail at exhaustion; it does not reveal allowance or guarantee delivery. Authenticated change-password is excluded.
- Guards control navigation, not authorization. Handle actual 401/403/404 responses. Display API strings as text; never render arbitrary descriptions/error messages as HTML.
- Normalize unknown security/error bodies safely. Not every error is ApiError: progress 409 can be ReadingProgress, security can be non-JSON, PDF errors can be Blob responses, and HEAD/204 have no body.

## PDF upload and reading

- Private upload: `POST /api/books/{bookId}/document`, FormData part `file`, required UUID `Idempotency-Key`. Let the browser set the multipart boundary.
- Public admin upload: `POST /api/public-books/{bookId}/document?fileName=...`, raw File/Blob body with `Content-Type: application/pdf` and UUID `Idempotency-Key`; never multipart on this route.
- Keep one UUID per intended upload across network retries; changing file/book/account means a new operation. Replay can return an inactive version: refetch active metadata/progress. Interrupted uploads restart; no resumable chunk API exists.
- Verify browser upload progress support with the selected Angular HTTP backend. Use supported XHR transport for upload progress if the fetch backend cannot emit it. Show indeterminate progress when byte totals are unknown; cancellation can still leave a committed upload.
- Client validation helps UX, but backend validates PDF content. Limits are deployment-configured: private default 200MB file/201MB multipart, not universal public limits. Do not read large PDFs into base64 strings.
- PDFs are protected streams with optional Range requests and documentId pinning. Plain iframe URLs cannot attach Bearer headers. Choose a reader with authenticated streaming/range transport, refresh handling and worker lifecycle support; a full Blob download is only an explicitly documented fallback with memory limits.
- Pin active `documentId` across metadata, progress and content. On mismatch/replacement, refetch before restoring any page. Destroy readers, abort requests and release workers/object URLs on exit.
- Start at backend `resumePage`; convert one-based indexing only at the library boundary. Do not set manual Book.completed from PDF progress.

## Reading synchronization

Save exactly `{documentId, currentPage, version, operationId}` to the appropriate private/public PUT. Page must be 1..totalPages. GET can return currentPage=0, resumePage=1, version=0 and null lastReadAt before the first save.

Persist the exact payload before dispatch. An ambiguous network retry retains all four fields. A new edit or reconciled revision uses a new operation UUID. Serialize accepted updates; debounce changes and retain unsent latest local intent. Do not fabricate client timestamp or last-write-wins fields.

Matching revision updates resume and advances version. `pagesRead` is maximum reached page; moving backward does not decrease it. Server percentage and completion are authoritative. A stale revision can merge a higher maximum but returns 409 while preserving server resume. Parse 409 ReadingProgress separately from 409 ApiError (replacement/reused operation). Never blindly overwrite a newer resume: present server versus local position and require an explicit choice before saving local intent with returned version/new UUID. Apply this consistently to workspace-shared public progress.

Cache pending work by authenticated identity, library, book and document; public state also includes workspace. Do not reuse it across logout/account changes. Maintain state while offline/tab is open and sync after reconnect. Persist pending updates for reload recovery if implemented. Do not promise offline PDF reading without an actual bounded, isolated PDF cache. Pagehide saves are best effort: authenticated PUT cannot rely on sendBeacon; durable pending work is the recovery mechanism.

## Google Drive and notifications

- Connect through the backend's authenticated POST, preserve its browser-binding cookie, open the returned Google authorizationUrl in the same browser, then recheck connection. Callback is backend HTML, not an Angular callback route or JSON API. Do not invent postMessage notifications.
- Use same-origin development proxy and production reverse proxy for connect/callback cookie handoff. Backend CORS does not enable credentials; adding cross-origin withCredentials alone is insufficient. Verify callback URL, cookie Path/SameSite/Secure and HTTPS forwarding together.
- Picker configuration is short-lived and memory-only. Backend handles OAuth secrets/refresh tokens; frontend sees a restricted public Picker key and scoped Google token. Import only selected `fileId`, never an arbitrary URL.
- Queue private import with UUID Idempotency-Key and `{fileId}`; poll boundedly until COMPLETED/FAILED. 202 means queued, not imported. Location is not CORS-exposed: construct the documented status URL from bookId/importId. Refetch active document/progress afterward. No public Drive import API exists.
- SSE needs authenticated fetch streaming; native EventSource cannot set Authorization. Implement chunk-safe SSE parsing, abort, token refresh, bounded reconnect backoff and Last-Event-ID replay/deduplication. Five-minute disconnects are expected. Do not invent PDF/progress events: only documented ready, book.created and public-book-request.reviewed events are available.

## Quality and delivery

Use accessible labels, keyboard controls, focus management, contrast and live feedback. Support desktop/mobile layouts with loading, empty, error, retry and offline states. Never hide failed saves behind a successful-looking progress label.

Test request paths/bodies/headers, refresh races, authorization states, idempotent retry, both 409 formats, document replacement, public shared progress, upload transports, Drive cookie flow and SSE reconnect. Use mocks for deterministic unit/component tests and a separate live-backend smoke checklist. Run installed test/build/lint tools; report unavailable tools rather than inventing successful checks. Document runtime config, same-origin proxy, SPA route fallback, CSP/PDF worker/Picker requirements, HTTPS and SSE buffering/timeouts.
