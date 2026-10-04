# Angular client implementation specifications

**Status: proposed client work.** This repository contains backend code only.
The requirements below apply to the separately maintained Angular application.
Inspect that project's existing source/toolchain first and preserve working features.
[API.md](API.md) owns implemented routes; [ANGULAR_AGENTS.md](ANGULAR_AGENTS.md)
owns client contribution rules. No release, test or deployment claim is made here.

Dependencies between items follow their order: architecture and transport precede
feature screens; reader state precedes synchronization. Each item requires focused
behavioral tests, documented configuration and a production build in the client
repository. Backend changes require their own scoped design and compatibility review.

## 1. Architecture and access design

**Objective:** Architecture and access design.

**Requirements and verification:**

Review API.md sections 1–5 and 16–17. Inspect the Angular repository without changing application code, dependencies or configuration. Identify whether an app already exists, Angular/Node/package-manager versions, routes, HTTP services, auth, styling, forms, tests and deployment setup. If it is empty, design a new Angular SPA.

Target stable Angular >=22 and Node 24. Verify exact official release compatibility; Node 24 must satisfy the selected Angular patch's engine requirements. Propose standalone feature boundaries, lazy routes, responsive accessible UI, typed API services, auth/refresh strategy, PDF transport, reading synchronization, and test strategy.

Produce a route/access matrix for unauthenticated users, workspace owners and Super Admin; an endpoint-to-feature matrix covering all 43 business operations; likely files and dependency choices; and work itemd acceptance criteria. Treat health as optional operational integration, the denied legacy root as unsupported, and backend callback as browser navigation rather than an Angular API call.

Explicitly identify missing private update/delete, /me/role discovery, pagination, billing and signed URLs. Login tokens do not establish a documented frontend role claim. Propose separate user/admin entry flows with server-authoritative permissions rather than inventing role detection. Explain same-origin Drive cookie handoff and authenticated PDF Range requirements.

Acceptance: an architecture/access/endpoint matrix and a reviewed implementation sequence exist before application changes.

## 2. Establish the Angular workspace and configuration

**Objective:** Establish the Angular workspace and configuration.

**Requirements and verification:**

Establish the app foundation. Reuse an existing app; scaffold in the current Booker_UI root only when empty and preserve these markdown files. Use stable Angular >=22 with matching CLI packages and a compatible Node 24 patch, strict TypeScript/templates and the installed CLI's supported defaults. Record the exact toolchain and commit a lockfile.

Create lazy route placeholders and the agreed feature folders, public injectable API configuration, and a development same-origin proxy targeting localhost:8080. Include /api and any documented documentation/health paths actually needed. Plan the Google backend callback path under the same browser origin; do not place secrets in environments. Use a browser SPA unless existing architecture requires otherwise.

Provide useful npm scripts using available tools, runtime pinning and a concise setup README. Establish the status checklist with every later work item pending. Verify install/version consistency, a production build and the generated baseline tests. Record the build/test results before feature work.

## 3. Typed contracts and HTTP/error foundation

**Objective:** Typed contracts and HTTP/error foundation.

**Requirements and verification:**

Read API.md sections 2–5 and field tables for all endpoint families. Build strict response/request interfaces and typed services for auth, workspace, private/public books, documents, progress, Drive and requests. Preserve direct arrays/objects, nullable fields, UUID strings, numeric safe book IDs and snake_case workspace usage keys. Model exact reading save payloads and distinct private/public roots.

Implement configurable backend URL construction and safe error normalization for ApiError, non-JSON security errors, network errors, Blob PDF errors and empty responses. Keep HTTP 409 ReadingProgress distinguishable from ApiError. Do not globally force JSON Content-Type on uploads. Model exact query filters without fabricated pagination/sort fields. Do not implement UI or invented API operations.

Add HTTP tests proving representative methods, paths, body shapes, nullable decoding, multipart/raw distinctions and both 409 response shapes. Verify tests/build and record contract coverage.

## 4. Authentication, refresh and access boundaries

**Objective:** Authentication, refresh and access boundaries.

**Requirements and verification:**

Read API.md sections 2 and 6. Implement signup and login forms with documented validation, token/session management, logout, functional bearer interceptor and navigation guards. Signup creates a workspace owner but does not return login tokens; use a documented login step afterward.

Implement single-flight rotating refresh, atomic replacement of both tokens, bounded one-time request retry and no refresh recursion. Scope Authorization to protected configured backend APIs. Use a documented token-storage choice; never fabricate an HttpOnly refresh-cookie contract. If sessions are shared across tabs, coordinate rotation and logout across tabs. Treat an ambiguous refresh network failure without repeatedly consuming the old token.

Use explicit owner/admin navigation entry flows: there is no documented role/profile endpoint or JWT role claim. Backend responses enforce actual privileges; a 403 is not a reason to log out or refresh. Super Admin must not automatically fetch workspace/private/progress APIs.

Test concurrent 401s, refresh failure, logout failure/local clearing, no bearer leakage, public auth requests, invalid token handling and role-restricted navigation. Keep password-management screens for the next work item.

## 5. Password recovery and account security

**Objective:** Password recovery and account security.

**Requirements and verification:**

Read API.md sections 6.5–6.7. Implement authenticated change-password, public forgot-password and emailed-link reset-password pages using exact documented request fields and constraints. Show the generic forgot-password response without exposing account existence. Handle 204 without JSON parsing and expired/used tokens safely.

Support pasting the emailed token when PASSWORD_RESET_URL is blank. If link delivery is configured, read the token from the agreed link query parameter and document that PASSWORD_RESET_URL must target this screen. Do not add a token lookup API. After a successful password change/reset invalidate local session state and require login. Avoid token leakage to logs, analytics and referrers; remove the reset token from browser history after capturing it for the form.

Test validation, incorrect current password, reset success/error, generic email confirmation and session cleanup. Run relevant checks.

## 6. Responsive shell and workspace dashboard

**Objective:** Responsive shell and workspace dashboard.

**Requirements and verification:**

Read API.md section 7. Implement responsive accessible navigation, route titles, keyboard focus, global feedback and the workspace dashboard. GET /api/workspace supplies name, plan, book_limit and books_used. Display quota state without introducing upgrade/payment or workspace-selection APIs.

Owner navigation includes private library, public library, requests and Drive/account settings. Admin entry includes public catalogue management and request review; it must work without a workspace call. Provide consistent loading, empty, forbidden, not-found, unavailable and offline views. Display dates according to the documented timestamp types without treating ApiError.dateTime as UTC.

Test navigation, owner/admin boundaries, workspace success/403/503 and quota formatting. Verify mobile/desktop layouts and production build.

## 7. Private book catalogue and creation

**Objective:** Private book catalogue and creation.

**Requirements and verification:**

Read API.md section 8 and 10.3. Implement private book list, detail and create form. Fields are title, author, publishedDate, description and completed, with documented validation. Preserve publishedDate as a string. Handle duplicate 409 and quota 403 distinctly.

Implement exact author/title search using only documented query parameters. The API returns unpaginated arrays; any client sorting/filtering/paging is local. Do not add private edit/delete or completion-toggle actions. Add document/progress summary slots using GET /api/books/reading-summaries with documented bookIds encoding and batches of at most 100 IDs; handle no-document null values and use backend percentages.

Provide loading, empty, failure and retry states with responsive cards/table and detail routes. Test field/body names, exact search, duplicate/quota behavior, summary batching and inaccessible book IDs. Leave upload/reader buttons routed to their later feature placeholders.

## 8. Authenticated public library and workspace requests

**Objective:** Authenticated public library and workspace requests.

**Requirements and verification:**

Read API.md sections 13.1–13.8 and 14.1–14.2. Implement authenticated public catalogue/detail and exact title/author filters. Owners can retrieve public reading summaries in batches <=100 and see clearly labeled workspace-shared progress. Super Admin can browse metadata/PDFs but must not fetch workspace progress.

Implement workspace request creation with {title, authorName}, not author, and workspace request history including PENDING/ACCEPTED/REJECTED states. Workspace history has no status-filter parameter; any local filter is client-side. Handle duplicate requests/existing public books and link accepted bookId to public detail. No cancellation or request-edit endpoint exists. The backend queues Super Admin notification email through a PostgreSQL outbox and RabbitMQ on submission; success does not confirm delivery. Do not send client-side email or accept administrator recipients from the user.

Test authenticated access, shared progress labeling, request validation/body/history, null accepted IDs while pending, 409 behavior and admin avoidance of workspace APIs.

## 9. Private PDF upload and safe retry

**Objective:** Private PDF upload and safe retry.

**Requirements and verification:**

Read API.md section 9 and 16 private upload workflow. Implement private upload/replace from an existing book's detail page: FormData part file and required UUID Idempotency-Key. Create metadata first if needed. Validate extension, nonempty file and configured limits for UX while treating backend PDF validation as authoritative.

Use an Angular HTTP transport that actually supports upload progress, with indeterminate fallback and cancellation. Do not load the file into base64 or manually set the multipart boundary. Retain one operation UUID for the same file/book/account retry; show that interrupted uploads restart rather than resume. Cancellation may still commit server-side, so reconcile afterward.

After success/replay refetch active document and progress because returned metadata may be an inactive version. Show replacement confirmation explaining that a new active document starts fresh progress; no document history/restore UI exists. Handle 403 quota, 413, 415, 503, network ambiguity and failure without losing metadata.

Test FormData/headers, UUID retention versus new-file change, progress/cancel/retry behavior, replay reconciliation and replacement state. Verify upload transport with a browser smoke test if a backend is available.

## 10. PDF reader and authenticated Range transport

**Objective:** PDF reader and authenticated Range transport.

**Requirements and verification:**

Read API.md private/public PDF metadata, GET/HEAD content endpoints and section 16. Select a maintained reader compatible with the installed Angular release after checking official documentation, peer dependencies, workers and authorization/range behavior. Explain the choice before adding its dependency.

Implement a shared reader with explicit private/public library context. Retrieve metadata and permitted progress, verify matching documentId, pin content using the documented documentId query parameter and start at resumePage. For Super Admin public viewing, do not request or save workspace progress; start at page 1 with a clear preview state.

Use authenticated PDF streaming/range requests, bearer refresh and cancellation without exposing tokens in URLs. Do not rely on an iframe to add Authorization. If using a full Blob fallback, document large-file memory limits and keep it out of the default large-PDF path. Verify 200/206, ETag/If-Range as appropriate, 401, 404, 409 and 416 behavior. Refresh metadata on replacement before applying saved pages.

Implement page navigation, zoom, current/total page display, loading/error/retry and accessible controls. Release workers, requests and object URLs on route exit. Emit normalized one-based page changes for the next work item; do not yet fabricate successful progress persistence.

Test initial page, indexing, auth/range headers, replacement race, admin preview, invalid page, worker cleanup and a real large-file browser smoke test when available.

## 11. Reading progress synchronization and conflict recovery

**Objective:** Reading progress synchronization and conflict recovery.

**Requirements and verification:**

Read API.md sections 10, 13.4–13.6 and the full synchronization table in section 16. Implement the reader's save coordinator using {documentId,currentPage,version,operationId}. Backend GET page 0 is valid; PUT requires 1..totalPages. Use server pagesRead, percentage, completed, lastReadAt and resumePage; manual Book.completed stays separate.

Debounce page changes, serialize saves and keep the exact submitted payload/UUID for uncertain retries. Distinguish newer unsent local edits from an in-flight payload. Persist pending work before dispatch, scoped by authenticated account/library/book/document and workspace for public reading. Flush best-effort on route close/background and reconnect; authenticated PUT is not a sendBeacon contract.

Implement both 409 shapes: revision conflict returns ReadingProgress and may already merge maximum-page progress; replacement/reused-operation returns ApiError. Preserve the newer server resume and show explicit local-versus-server recovery choices rather than blindly overwriting it. A chosen reconciled save uses returned version and a new operation UUID. Discard/quarantine old-document work after replacement. Public progress is shared by all workspace readers.

Maintain local position during offline reading in the open tab. Recover pending work after reload if durable queueing is implemented; promise offline PDF availability only if an actual isolated bounded cache exists. Clear sensitive queues/session state on logout/account change.

Test 93/144 = 64.58% from server responses, backward navigation preserving pagesRead, final-page completion, exact accepted retry, stale/future revision, both 409 shapes, concurrent saves, offline reconnect, tab close recovery, document replacement and account isolation.

## 12. Google Drive connect, Picker and import

**Objective:** Google Drive connect, Picker and import.

**Requirements and verification:**

Read all of API.md section 11 and the Drive workflow in section 16. Implement connection status, connect/disconnect and private-book PDF import. Keep the backend OAuth callback on its backend path; it returns HTML and is not an Angular token-exchange route.

Use a same-origin dev/production API proxy so the connect response's browser-binding cookie reaches the browser callback. Document callback registration, cookie path/Secure/SameSite and forwarded HTTPS requirements. Open only the returned Google consent URL in that same browser, handle popup blocking/cancellation and recheck connection on return; do not assume a postMessage contract. Cross-origin withCredentials alone is insufficient under the documented CORS setup.

Load Google Picker using memory-only short-lived backend configuration. Restrict selection to PDFs and import {fileId} rather than a URL, with a persisted UUID Idempotency-Key. Poll the documented status path from bookId/importId with cancellation/backoff until COMPLETED or FAILED; 202 is not completion. Handle reconnect required, revoked grant, disabled/unavailable integration and failed asynchronous jobs. Refetch active document/progress on completion. Disconnect does not delete imported PDFs.

No OAuth client secret or refresh token belongs in Angular; no public-book Drive import exists. Test selection/payload, durable import retry, polling/terminal failure and connection errors. Provide a manual same-browser cookie/OAuth smoke checklist, clearly separated from mocked tests.

## 13. Workspace live notifications

**Objective:** Workspace live notifications.

**Requirements and verification:**

Read API.md section 12 and SSE payloads in section 16. Implement an authenticated fetch streaming SSE client for owners using the shared token/refresh coordination; native EventSource cannot add Bearer headers.

Parse events across arbitrary stream chunks, multiline data, comments, id, event and retry fields. Preserve opaque cursor strings, update cursor only after processing, deduplicate replay and reconnect with Last-Event-ID and bounded jitter/backoff. Handle expected five-minute disconnects, malformed/future cursor, capacity 503, token expiry and logout teardown. Scope cursor/state to workspace/session.

Handle ready, schemaVersion-2 book.created and schemaVersion-1 public-book-request.reviewed. Refresh affected list/history and show accessible feedback. Do not invent document/progress/import completion events; Drive polling and reader synchronization remain separate. Do not connect SSE for Super Admin without a workspace.

Test split UTF-8/chunks, multiline frames, heartbeat, duplicate events, cursor persistence, expiry/reconnect, invalid cursor recovery, token refresh and abort cleanup. Document reverse-proxy buffering and timeouts.

## 14. Super Admin catalogue management and request review

**Objective:** Super Admin catalogue management and request review.

**Requirements and verification:**

Read API.md sections 13.9–13.12 and 14.3–14.5. Implement admin public book create, full metadata PUT and DELETE with destructive-action confirmation. Admin login uses the ordinary login API; no signup role selection, role assignment or profile endpoint exists. Backend 403 is authoritative.

Implement public PDF upload as raw File/Blob application/pdf with required fileName query parameter and UUID Idempotency-Key. Do not reuse private multipart encoding or private quota assumptions. Use the established upload progress/retry coordinator and refetch active metadata after replay.

Implement admin request list with exact optional uppercase status. Acceptance sends multipart metadata as an application/json Blob with publishedDate/description/completed plus PDF part file. Title and author come from the pending request. Acceptance has no documented Idempotency-Key requirement: never automatically replay a review after ambiguous success; refetch request status first. Rejection uses the exact body/no-body contract documented in section 14.5, not an invented reason field.

Handle already-reviewed 409, validation/PDF/storage failures, forbidden access and multipart limits. Successful review is not proof that notification email was delivered. Refresh catalogue/request views after decisions; do not fetch private/workspace/progress APIs in admin mode.

Test CRUD methods/payloads, raw-versus-multipart upload, metadata Blob content type, accept/reject conflicts, ambiguous decision reconciliation and 403 boundaries.

## 15. UX polish, security and integration tests

**Objective:** UX polish, security and integration tests.

**Requirements and verification:**

Review the implementation against API.md and the status checklist. Polish book list/detail/reader UX with backend progress, continue-from-page labels, nullable last-read dates, document-free upload/import actions, workspace-shared public progress and distinct manual completion. Do not introduce unsupported actions.

Review accessibility, keyboard focus, mobile/desktop layout, loading/errors/offline/saving indicators and cleanup. Review token leakage, safe text rendering, account/workspace cache isolation, file URL/worker lifetime, OAuth handoff, admin restrictions and retry/conflict handling. Document dependency versions and actual reader memory behavior.

Complete meaningful unit/component and browser integration tests for auth rotation, catalogue/search, private upload, public raw upload, admin review, reader restore, offline pending updates, replacement races, public shared conflicts, Drive polling and SSE reconnect. Use deterministic mocks for CI and a separate live-backend smoke plan with owner accounts from two workspaces and Super Admin. Never count a mocked 403 as proof of backend isolation.

Run available lint, full tests, production build and browser checks. Report exact commands, passed/failed/skipped checks and remaining gaps. Fix issues within frontend scope; report backend contract gaps instead of changing the server.

## 16. Deployment documentation and final review

**Objective:** Deployment documentation and final review.

**Requirements and verification:**

Finish the implementation status checklist and documentation using only verified implemented behavior. Review all changed files for unnecessary complexity and backward compatibility. Run final checks appropriate to the latest changes.

Document supported Angular/Node versions, install/start/test/build commands, public runtime API configuration, local same-origin proxy and production HTTPS reverse-proxy routing. Document SPA route fallback while preserving backend OAuth callback routing, callback URL registration, CORS origin configuration if used, SSE buffering/timeouts, PDF Range/header forwarding, worker asset/CSP requirements, Google Picker CSP requirements and reset-link frontend URL. Never place backend JWT/Google/SMTP/storage secrets in the UI deployment bundle.

Provide route and endpoint coverage, owner/admin access behavior, token-storage limitations, PDF library/transport choice, reading conflict/offline strategy, Google Drive flow, tests actually run and a live deployment smoke checklist. Include unresolved /me-role discovery, unsupported backend features and any external OAuth configuration as explicit gaps where applicable.

Deliver a concise summary of implemented features, architecture, changed files, configuration required, validation and remaining TODOs. Do not claim deployment, live OAuth, large-file performance or real authorization tests succeeded unless they were actually exercised.
