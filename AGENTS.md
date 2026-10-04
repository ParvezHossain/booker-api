# Repository engineering guide

## Scope and architecture

This repository is the Booker Spring Boot backend. Android and Angular clients
are maintained separately; their specifications here do not establish client
implementation status. Inspect affected source, tests and documentation before
editing. Preserve unrelated changes and existing architecture. Explain the
purpose of changes to existing classes before modifying them.

| Package | Responsibility |
| --- | --- |
| `controller`, `auth`, `saas`, `library` controllers | HTTP routing, validation, status/headers and DTO mapping |
| `service`, feature services | Authorization, business rules and transaction boundaries |
| `repository`, feature repositories | JPA book queries and parameterized JDBC persistence |
| `document`, `storage` | PDF metadata/validation and provider-independent byte storage |
| `reading` | Account-private and workspace-shared reading revisions/receipts |
| `drive` | Browser-bound OAuth, protected credentials and durable import worker |
| `notification` | Workspace-scoped durable events and SSE delivery |
| `library` email queue | Transactional outbox, confirmed bounded RabbitMQ publication, sequential SMTP delivery and delayed retries |
| `auth` recovery email queue | Encrypted short-lived outbox, opaque RabbitMQ receipt IDs, lease ownership and SMTP outside database locks |
| `config`, `exception`, `security` | Security policy, OpenAPI, safe errors and token helpers |

Keep controllers focused on HTTP concerns. Use constructor injection and immutable
request/response records. Do not expose JPA entities or internal storage models as
API responses. Reuse existing JPA/JDBC patterns; do not add a second persistence,
networking or dependency-injection stack without a demonstrated need.

## Java and Spring conventions

- Build with Java 25 and the Maven wrapper. Follow surrounding naming and formatting.
- Validate request fields with Jakarta Validation; validate configuration at startup.
- Keep dependencies managed by the Spring Boot parent where possible. Justify new
  dependencies and remove verified unused dependencies without changing contracts.
- Services own transactions. Use read-only transactions for consistent reads and
  explicit write boundaries; preserve account/book/workspace lock ordering.
- Do not move external I/O into transactions unnecessarily. Request acceptance
  currently holds its transaction during PDF storage/parsing; changes must retain
  rollback/file cleanup behavior.
- Keep checked-exception rollback rules explicit where required, especially public
  request acceptance. File storage and database commits are separate resources.
- Log useful failure categories and route context; never log passwords, bearer/reset
  tokens, OAuth secrets, PDF bodies or database failing-row values. Unexpected server
  errors need diagnostic context, with controlled access to logs.
- Return safe `ApiError` messages through `GlobalExceptionHandler`; preserve proper
  HTTP statuses/headers. Security-filter, HEAD, Range and progress-conflict bodies
  are separate contracts, not automatically `ApiError`.

## Security and API invariants

- Derive identity from `WorkspacePrincipal`. Never trust client workspace/user/role
  selectors. Authorize before reading documents, pinning versions or serving ranges.
- Private books belong to a workspace; private progress and Drive credentials belong
  to an account. Public progress belongs to a workspace. Super Admin has no workspace
  and must not gain access to customer private books.
- Preserve Bearer and legacy Basic compatibility. Password changes invalidate old
  account tokens; refresh is single use. Serialize refresh/token issuance with
  password replacement using the existing account lock.
- Use parameterized SQL. Dynamic SQL identifiers may come only from closed internal
  enums, never request input.
- Preserve JSON names, numeric book IDs, UUID document/operation IDs, exact author/title
  uniqueness, direct responses and current `/api` paths. Breaking changes require an
  explicit compatibility plan and matching client documentation.
- Keep manual `Book.completed` separate from document reading completion. Percentages
  derive from maximum page reached; resume position supports backward navigation.
- Progress retries retain their exact body and operation UUID. Preserve revision
  conflict semantics and both 409 body shapes. Document replacement resets visible
  progress without transferring old-document updates.
- PDF bytes stay outside PostgreSQL behind `FileStorageService`. Stream large files;
  generate opaque keys, sanitize filenames and enforce existing validation/quotas.
  Keep at most one active immutable document per book; retained versions count toward
  private quota. Public raw upload has distinct documented quota behavior.
- Keep OAuth browser binding, state expiry/single use, PKCE and account-bound encryption.
  Do not import arbitrary URLs or put backend secrets in client specifications/code.

Workspace book requests are capped at ten successful creations per UTC calendar
month across all workspace accounts and plans. Preserve workspace row locking,
READ COMMITTED counts, post-lock database time and matching request `created_at`.
All statuses count; invalid/duplicate/rolled-back requests do not. Do not bypass
this invariant in another submission path, delete audit history to reset quota,
or remove 429/Retry-After behavior and its CORS/OpenAPI documentation.

Successful password recovery is capped per account per UTC calendar month by
PASSWORD_RESET_MONTHLY_LIMIT (positive integer, default three). Preserve the account
row lock, READ COMMITTED history counts, post-lock database time and atomic history/
password/session updates. Email issuance, failed attempts, rollback and authenticated
password changes do not count. Forgot-password remains generic 202 without issuance
at exhaustion; valid-token reset returns 429/Retry-After while invalid tokens stay 400.
Do not delete current-month password_reset_history to restore allowance.

Request/decision email delivery keeps PostgreSQL authoritative. Publish only opaque
receipt IDs, require persistent messages/confirmed mandatory routing, and preserve
single-active-consumer, prefetch/concurrency 1 and bounded queues. Acknowledge only
after database delivery/retry state commits. Never replace outbox persistence with
a direct publish inside the request transaction. Preserve delayed retries and failed
receipt recovery; avoid immediate requeue loops or unbounded publisher batches.

Password recovery commits its token digest and encrypted email receipt atomically,
with no SMTP or RabbitMQ I/O on the request thread. Keep the dedicated stable AES-GCM
key, account/receipt metadata binding, original expiry and stale-token checks. Only
opaque UUIDs go to the separate bounded recovery queue. SMTP runs outside transactions;
claim/finalization use short transactions with lease ownership checks. Never let an
old worker restore a replaced token or finalize a different lease. Acknowledge after
completion/retry state commits; preserve delayed retries, parking and expiry cleanup.

## Database and configuration

Any schema change needs a new Flyway migration in
`src/main/resources/db/migration`. Never edit applied migrations or erase history.
Include indexes/constraints and populated-upgrade tests where appropriate. Hibernate
validates schema; it must not become the production migration mechanism. There are
no undo migrations; document forward-fix or database/file restore requirements.

Use environment-backed settings for deployment choices. Keep `.env.example` safe
and current, document defaults in `docs/operations.md`, and pass new settings through
Compose when applicable. `.env` files are local secrets; Maven does not load them.
Never run destructive tests/migrations against a customer or development database.

## Testing and documentation

Run `./mvnw -B clean verify` against disposable PostgreSQL and RabbitMQ, with
`BOOK_EVENTS_TEST_JDBC_URL` set so event persistence is exercised. Test observable
behavior rather than restating implementation. Cover workspace/role isolation,
invalid input, retries, concurrency, rollback and failure paths for affected features.
Do not disable tests to obtain a passing build. Verify Docker changes when available
and report unavailable infrastructure honestly.

`API.md` is the HTTP reference; controller OpenAPI annotations must agree with it.
Keep its inventory synchronized; `OpenApiCoverageTest` checks registered mappings.
`README.md` is the entry point; feature guides under `docs/` own detailed behavior.
`PROMPTS.md` is the proposed engineering backlog; client specifications remain in
`ANDROID_PROMPTS.md` and `ANGULAR_PROMPTS.md`. Keep implemented behavior separate from
requirements and remove obsolete session narration/test totals. Preserve useful
information and compatibility links before removing or consolidating documents.

## Definition of done

- Changes satisfy the scoped requirements and preserve unrelated work.
- APIs, validation, security scopes and migrations are reviewed for compatibility.
- Applicable tests/build pass; failures, skips and unverified external integrations
  are reported with commands actually executed.
- Configuration examples, OpenAPI and relevant documentation match the code.
- No secrets, generated runtime files, unsupported feature claims or unnecessary
  abstractions are introduced. Git history is preserved.
