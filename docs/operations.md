# Development and operations

[README](../README.md) owns the quick start. This guide owns detailed runtime
configuration, test isolation, schema upgrades and production operations.

## Configuration

`application.properties` maps environment variables to Spring settings. Compose
loads `.env`; Java/Maven require exported variables or an external configuration
provider. Copy `.env.example`, replace placeholders, and never commit secrets.
Environment-specific `.env.*` files are ignored; the safe example remains tracked.
Docker excludes secret files, stored PDFs and local tooling from its build context.

If startup reports `Could not resolve placeholder 'JWT_SECRET'`, the Java process
has not received that setting. A successful `echo "$JWT_SECRET"` in a terminal
does not prove it is exported or available to an IDE launch. For source runs use
`bash scripts/run-local.sh`, which loads and exports the trusted `.env`. For IDE
runs set the variables in the application's Run/Debug configuration (or configure
its environment-file support), then stop and restart the application. Do not
commit credentials in shared IDE configurations or add a fallback signing key.

| Variable | Default / requirement |
| --- | --- |
| `PORT` | 8080; source server port or Compose host mapping (container remains 8080) |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/android`; Compose uses its postgres service |
| `DATABASE_USERNAME` | `admin`; Compose uses its configured local database account |
| `DATABASE_PASSWORD` | Required; no source-mode fallback; Compose requires a nonempty value |
| `JWT_SECRET` | Required Base64 encoding of at least 32 random bytes; generate with `openssl rand -base64 32` |
| `JWT_ACCESS_TTL` | `PT15M`; at least one second |
| `JWT_REFRESH_TTL` | `P7D`; must exceed access TTL |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200`; comma-separated exact browser origins |
| `SSE_MAX_CONNECTIONS` | 200 per backend instance; positive |
| `SSE_POLL_MILLIS` | 1000; positive polling interval |
| `BOOK_STORAGE_DIRECTORY` | `./data/books` in source mode; Compose fixes `/app/data/books` |
| `BOOK_MAX_FILE_SIZE` | `200MB`; private upload byte limit and servlet multipart file cap |
| `BOOK_MAX_REQUEST_SIZE` | `201MB`; servlet multipart request cap, including admin acceptance |
| `BOOK_WORKSPACE_STORAGE_LIMIT` | `5GB`; all retained private document versions count |
| `BOOK_MAX_PAGES` | 20000; private PDF page limit |
| `GOOGLE_DRIVE_ENABLED` | false; disabled unless OAuth is configured |
| `GOOGLE_DRIVE_CLIENT_ID` | OAuth Web application client ID, required when enabled |
| `GOOGLE_DRIVE_CLIENT_SECRET` | Backend-only OAuth secret, required when enabled |
| `GOOGLE_DRIVE_REDIRECT_URI` | Exact registered backend callback; HTTPS except local HTTP |
| `GOOGLE_DRIVE_ENCRYPTION_KEY` | Separate stable Base64 32-byte AES key, required when enabled |
| `GOOGLE_DRIVE_PICKER_API_KEY` | Restricted public browser key; required for Picker |
| `GOOGLE_DRIVE_PROJECT_NUMBER` | Numeric Cloud project number used as Picker appId |
| `SUPER_ADMIN_EMAIL`, `SUPER_ADMIN_PASSWORD` | Optional pair for initial provisioning; valid email and 12–64 character password |
| `SMTP_HOST`, `SMTP_PORT` | Blank host / 587; configure delivery provider |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | Backend-only provider credentials |
| `SMTP_AUTH`, `SMTP_STARTTLS` | true / true; STARTTLS is required when enabled |
| `PASSWORD_RESET_FROM` | Provider-approved password-reset sender; fallback sender for request/confirmation mail |
| `BOOK_REQUEST_EMAIL_FROM` | Request/decision sender; blank falls back to `PASSWORD_RESET_FROM`, then `SMTP_USERNAME`. Set explicitly if the SMTP username is not a provider-approved email address |
| `PASSWORD_RESET_URL` | Optional; blank emails a copyable token. If set, HTTPS client landing page, no fragment; reset token appended as query parameter |
| `PASSWORD_RESET_TOKEN_PAGE_URL` | Optional reachable URL of the backend `/password-reset-token` helper; adds an email copy action using `#token`. HTTPS required except localhost/private IPv4 LAN development; no credentials, query or fragment |
| `PASSWORD_RESET_TIME_ZONE` | `Asia/Dhaka`; IANA timezone for account email time/expiry display, validated at startup. Browser copy page uses the device timezone |
| `PASSWORD_RESET_TTL` | `PT30M`; positive, at most 24 hours |
| `PASSWORD_RESET_MONTHLY_LIMIT` | `3`; positive integer, successful resets per account per UTC calendar month; validated at startup |
| `PASSWORD_RESET_EMAIL_ENCRYPTION_KEY` | Dedicated stable Base64 32-byte AES key; required to accept recovery; blank yields recovery 503, malformed nonblank fails startup |
| `PASSWORD_RESET_EMAIL_ENABLED` | true; false pauses background publisher/listener; accepted encrypted receipts remain pending |
| `PASSWORD_RESET_EMAIL_QUEUE` | booker.password-reset-emails; distinct from request-email queue/dead queue |
| `PASSWORD_RESET_EMAIL_POLL_MILLIS` | 1000; fixed delay between publisher passes |
| `PASSWORD_RESET_EMAIL_BATCH_SIZE` | 20; range 1–100; bounds publishing and obsolete-receipt cleanup |
| `PASSWORD_RESET_EMAIL_QUEUE_LIMIT` | 1000; range 1–100000; declaration-time main/dead queue bound |
| `PASSWORD_RESET_EMAIL_MAX_ATTEMPTS` | 5; range 1–20; parks exhausted delivery |
| `PASSWORD_RESET_EMAIL_RETRY_SECONDS` | 30; range 1–3600; exponential retry capped at one hour |
| `PASSWORD_RESET_EMAIL_REDISPATCH_SECONDS` | 300; range 30–86400; confirmed-publication recovery window |
| `PASSWORD_RESET_EMAIL_LEASE_SECONDS` | 60; range 30–3600; set above expected SMTP duration; stale ownership cannot finalize a new lease |
| `PASSWORD_CHANGE_EMAIL_FROM` | Blank uses PASSWORD_RESET_FROM; provider-approved confirmation sender |
| `PASSWORD_CHANGE_EMAIL_ENABLED` | true; false pauses confirmation delivery while audit/outbox acceptance continues |
| `PASSWORD_CHANGE_EMAIL_QUEUE` | booker.password-change-emails; main/dead names must be distinct from request and recovery queues |
| `PASSWORD_CHANGE_EMAIL_POLL_MILLIS` | 1000; range 100–3600000, fixed publishing delay |
| `PASSWORD_CHANGE_EMAIL_BATCH_SIZE` | 20; range 1–100 |
| `PASSWORD_CHANGE_EMAIL_QUEUE_LIMIT` | 1000; range 1–100000, main/dead quorum queue bounds |
| `PASSWORD_CHANGE_EMAIL_MAX_ATTEMPTS` | 5; range 1–20, then park for operator recovery |
| `PASSWORD_CHANGE_EMAIL_RETRY_SECONDS` | 30; range 1–3600, exponential delay capped at one hour |
| `PASSWORD_CHANGE_EMAIL_REDISPATCH_SECONDS` | 300; range 30–86400, publication recovery window |
| `PASSWORD_CHANGE_EMAIL_LEASE_SECONDS` | 60; range 30–3600, set above expected SMTP send time |
| `BOOK_REQUEST_EMAIL_ENABLED` | true; pauses publisher/listener when false, leaving receipts pending |
| `RABBITMQ_HOST`, `RABBITMQ_PORT` | 127.0.0.1 / 5672; Compose uses rabbitmq / 5672 |
| `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | booker / required deployment secret; Compose requires nonempty password |
| `RABBITMQ_VHOST`, `RABBITMQ_SSL_ENABLED` | `/` / false locally; use isolated vhosts and TLS in production |
| `RABBITMQ_HEALTH_ENABLED` | true; broker health is independent of request/recovery delivery pause flags |
| `BOOK_REQUEST_EMAIL_*` delivery limits | See [queue settings](request-email-queue.md#configuration) for batch, capacity, retry and recovery defaults |
| `MANAGEMENT_OTLP_METRICS_EXPORT_URL` | Source default `http://localhost:4318/v1/metrics`; Compose supplies empty value |
| `MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED` | true; set false locally when no OTLP metrics collector is running |

ISO-8601 durations are used for token lifetimes. Spring DataSize units are used
for byte limits. Compose binds PostgreSQL, RabbitMQ AMQP/management and Grafana/OTLP ports to loopback;
application port 8080 is exposed for local clients. Its database name/user are a
local baseline, not a production provisioning policy. Deploy production settings
through your platform's secret/configuration manager.

Additional scheduled worker settings include Spring properties: `books.google-drive.poll-millis` and `initial-delay` default to 2000;
`books.storage.cleanup-poll-millis` defaults to 30000. The email publisher poll
defaults to 1000 and has the `BOOK_REQUEST_EMAIL_POLL_MILLIS` alias; other email
limits and broker recovery are documented in [queue operations](request-email-queue.md). Supply them through external Spring configuration when needed.

### Google Drive

Enable Drive and Picker APIs in the same Cloud project and configure OAuth consent
and test users. Create a Web application OAuth client, register the exact callback,
and provide the client credentials and numeric project number. The example
`http://localhost:4200/api/integrations/google-drive/callback` requires a frontend
proxy forwarding `/api` to this backend. Connect and callback must use the same
browser origin so the binding cookie survives. The callback is backend HTML, not
an SPA route. Restrict the public Picker key to Picker API and frontend website
patterns, including `https://docs.google.com/*` for Picker's iframe.

Generate a separate encryption key with `openssl rand -base64 32`. All replicas
must share it. Losing/rotating it requires account reconnection or a deliberate
re-encryption procedure. No credentials belong in Android/Angular bundles.
[PDF reading](book-reading.md) describes state, PKCE, encrypted credentials,
selected-file scope and import retry behavior. Mocked tests do not validate live
Google consent, provider restrictions or deployment cookie forwarding.

### SMTP and Super Admin

Password reset requires mail transport, sender and `PASSWORD_RESET_EMAIL_ENCRYPTION_KEY`.
Generate the dedicated key with `openssl rand -base64 32` and keep it stable across
replicas/restarts; do not reuse JWT/Drive keys or expose it to clients. Leave `PASSWORD_RESET_URL` blank
for emailed tokens that can be pasted into Swagger or an Android reset form;
set an HTTPS client landing page to email reset links instead. Reset email includes
HTML and plain-text alternatives. Set `PASSWORD_RESET_TOKEN_PAGE_URL` to the reachable
backend `/password-reset-token` URL to add a copy-page button. The helper is public,
uses no external scripts/analytics and never receives the token at the server.
Use a public HTTPS origin in production. HTTP private-LAN testing may need manual
copying when the browser blocks clipboard access. Email clients cannot reliably
run clipboard scripts directly; the email action opens this helper in a browser.
Book-request and decision emails need transport and the same sender, but not a reset landing page.
SMTP operations have five-second connection/read/write timeouts. Missing reset
configuration returns 503; request review still commits with email pending.
See [passwords](password-management.md) and [requests](public-library-requests.md).

Forgot-password commits token and encrypted outbox receipt then returns generic 202
without broker/SMTP network I/O. The separate bounded RabbitMQ queue publishes opaque
IDs and delivers SMTP outside account/receipt locks, with delayed retries and lease
recovery. Broker/provider outages leave receipts pending; original issuance expiry
is never renewed. PASSWORD_RESET_EMAIL_ENABLED=false pauses delivery but still
accepts configured eligible requests. See [recovery queue workflow and operations](password-reset-email-queue.md).

Password recovery permits `PASSWORD_RESET_MONTHLY_LIMIT` completed resets per account
per UTC calendar month (default 3), persisted in `password_reset_history`. Email
issuance and authenticated password changes do not consume it. At the limit,
forgot-password keeps generic 202 without sending mail; a valid token submitted to
reset-password returns 429 and `Retry-After` seconds until the next UTC month.
Restart all replicas with the same positive limit after changing `.env`; source/IDE
runs must export it. Email/browser timezone settings do not alter month boundaries.
Do not delete current-month history to bypass the allowance. Invalid tokens and
rolled-back/failed operations do not consume allowance.

Provision Super Admin once using both bootstrap settings and a dedicated email.
Startup will not reset an existing admin password or promote an owner. Remove
bootstrap secrets after provisioning. New book requests resolve recipients from
persisted SUPER_ADMIN accounts and enqueue one email per administrator, independent
of removed bootstrap settings. No new notification-recipient variable is needed.
Without any provisioned admin, requests succeed with a warning but no admin receipt;
newly provisioned administrators do not receive past submission notifications. The normal login endpoint handles both roles;
Super Admin cannot read private customer libraries and has no workspace progress.

## Workspace book-request quota

Book requests have a fixed shared limit of 10 successful submissions per UTC
calendar month for every workspace/plan. No configuration or migration is needed:
existing current-month history counts immediately and the V12 workspace/time index
supports enforcement. 429 responses include `Retry-After`; all accounts in a
workspace share the same allowance. Reviews and RabbitMQ retries do not reset or
consume additional slots. See the [authoritative quota policy](public-library-requests.md#monthly-workspace-request-limit).

Do not delete request history or rewrite `created_at` to bypass this limit; the
same records are request audit and quota state. Backup restoration preserves the
allowance. Gateway controls are still needed for invalid/duplicate requests and
other traffic because a monthly product quota is not a burst limiter.

## RabbitMQ

Compose adds RabbitMQ 4.2 management with a persistent `rabbitmq-data` volume,
512m memory and 1.0 CPU limits (customize through a Compose override),
health-gated backend startup and loopback-only ports 5672/15672. Set a strong
`RABBITMQ_PASSWORD` before starting. Source mode must export the broker credentials;
Java does not read `.env`. Queue delivery is enabled by default and aggregate health
includes RabbitMQ. Broker outage does not roll back submitted requests: their
receipts remain in PostgreSQL, but health can report unavailable infrastructure.

For IDE/source runs, use `RABBITMQ_HOST=127.0.0.1` and `RABBITMQ_PORT=5672`.
An existing `localhost` override can resolve to another loopback address where
Compose does not publish AMQP. Restart the application after changing its run
environment. Containers use the Compose service name `rabbitmq` instead.

An `ACCESS_REFUSED` authentication failure means the broker was reached but
rejected the login. Source/IDE runs must receive the same username and password
as the broker; see [source startup](../README.md#run-from-source) for exporting
a trusted local `.env`. Changing broker default credentials in `.env` does not
update users already stored in the persistent RabbitMQ volume.

Compose mounts `rabbitmq.conf` read-only to set the broker memory alarm at 256MiB,
below the container memory limit. Use this file rather than the deprecated
`RABBITMQ_VM_MEMORY_HIGH_WATERMARK` environment variable.

Use a protected broker/vhost, TLS and production quorum-cluster capacity. All
replicas must share queue names/vhost/database. There is no direct SMTP fallback
when `BOOK_REQUEST_EMAIL_ENABLED=false`; it deliberately pauses delivery.
[Queue topology, saturation, retries, recovery and upgrade rules](request-email-queue.md)
are maintained in one feature guide.

See [password change confirmation operations](password-change-notifications.md) for
connection-IP/forwarded-header behavior, audit access, retries, leases and parked receipt recovery.

Activity history capture and the workspace/Super Admin read APIs require no additional
`.env` variables. See [security history](account-security-history.md) for scope, bounded
cursor queries, sensitive metadata handling and retention. SMTP/RabbitMQ pause flags
do not disable audit capture.

## Testing

Use PostgreSQL 18 and RabbitMQ 4.2 to match Compose/CI, with **disposable services**. Tests add
accounts/books/files and migration tests create/drop schemas. They must never
point at production or a development database you need to preserve.

One local option, in a separate terminal:

```sh
docker run --rm --name booker-test-db \
  -e POSTGRES_DB=booker_test -e POSTGRES_USER=booker_test \
  -e POSTGRES_PASSWORD="$BOOKER_TEST_PASSWORD" \
  -p 127.0.0.1:55432:5432 postgres:18
```

Start an isolated broker as well, using a disposable `BOOKER_TEST_RABBIT_PASSWORD`:

```sh
docker run --rm --name booker-test-rabbit -d \
  -e RABBITMQ_DEFAULT_USER=booker_test \
  -e RABBITMQ_DEFAULT_PASS="$BOOKER_TEST_RABBIT_PASSWORD" \
  -p 127.0.0.1:5673:5672 rabbitmq:4.2-management
docker exec booker-test-rabbit rabbitmq-diagnostics -q ping
```

Wait until both services are ready. Set `BOOKER_TEST_PASSWORD` first to a disposable secret. Wait until PostgreSQL is
ready, then export settings in the build terminal:

```sh
export DATABASE_URL='jdbc:postgresql://localhost:55432/booker_test'
export DATABASE_USERNAME='booker_test'
export DATABASE_PASSWORD="$BOOKER_TEST_PASSWORD"
export JWT_SECRET="$(openssl rand -base64 32)"
export BOOK_STORAGE_DIRECTORY="$(mktemp -d)"
export MANAGEMENT_OTLP_METRICS_EXPORT_URL=''
export BOOK_REQUEST_EMAIL_ENABLED=false
export PASSWORD_RESET_EMAIL_ENABLED=false
export PASSWORD_CHANGE_EMAIL_ENABLED=false
export RABBITMQ_HOST=localhost
export RABBITMQ_PORT=5673
export RABBITMQ_USERNAME=booker_test
export RABBITMQ_PASSWORD="$BOOKER_TEST_RABBIT_PASSWORD"
export BOOK_EVENTS_TEST_JDBC_URL="${DATABASE_URL}?user=${DATABASE_USERNAME}&password=${DATABASE_PASSWORD}"
./mvnw -B clean verify
```

Use a URL-safe test password or encode it for the JDBC query URL. Spring tests use
the exported datasource; the low-level event test connects using its JDBC URL.
Omitting `BOOK_EVENTS_TEST_JDBC_URL` skips that test. General Spring test contexts
keep all three email queues disabled; `RequestEmailRabbitIntegrationTest`,
`PasswordResetEmailRabbitIntegrationTest` and `PasswordChangeEmailRabbitIntegrationTest`
each enable their feature with an isolated schema/queue, exclude unrelated JobRunr setup for that schema, and use
real RabbitMQ with mocked SMTP. `AuthHistoryIntegrationTest` also uses its own
disposable schema to verify authorization, pagination and atomic login audit. Broker tests are required, not optional. Check Surefire reports rather
than assuming a successful build exercised every case. Some PDF integration tests
use their own `booker-*-tests` directories under the Java temp directory; use an
isolated environment for concurrent suites and do not delete files owned by
another run. Test-specific limits intentionally differ from production defaults.

Database-free focused examples:

```sh
./mvnw -B -Dtest=GlobalExceptionHandlerTest,ReadingCompletionTest,LocalFileStorageServiceTest test
```

Coverage includes workspace/role isolation, password revocation/reset concurrency,
PDF validation/storage failures/Range/HEAD, document retry/replacement, reading
conflicts, public request rollback, email receipts, Drive simulation and populated
schema upgrades. `OpenApiCoverageTest` compares controller mappings to generated
OpenAPI and the Markdown inventory. It does not verify external providers or client
application code. Qodana analysis is configured separately; no explicit severity/coverage threshold is configured.

## CI

`.github/workflows/ci.yml` runs on pushes and pull requests with read-only contents
permissions. It installs Java 25, caches Maven, runs `clean verify` with PostgreSQL
18, RabbitMQ 4.2 and an ephemeral signing key, fails if tests were skipped, and builds the Docker
image. The container build packages with tests skipped only after Maven verification.
No registry publishing, deployment credentials or production deployment is configured.

## Database migrations and upgrades

Flyway migrations are immutable; Hibernate uses `ddl-auto=validate` and open-in-view
is disabled. Current schema responsibilities:

| Migration | Responsibility |
| --- | --- |
| V1–V2 | Initial books and historical seed catalogue |
| V3 | Transactional event cursor/log/creation trigger |
| V4 | Workspaces/accounts; legacy books/events assigned to a reserved workspace |
| V5 | Hashed refresh sessions |
| V6 | Immutable documents, account progress and retry receipts |
| V7 | Drive connections, OAuth states and import queue |
| V8 | Document/progress reference indexes |
| V9 | Exact workspace author/title identity, removed ISBN, schemaVersion 2 events |
| V10 | Public books, Super Admin, workspace progress and file deletion receipts |
| V11 | Credential versions and reset tokens |
| V12 | Public book requests, decision email receipts and typed events |
| V13 | Typed per-recipient request/decision outbox receipts, subjects and optional HTML; preserves pending decision emails |
| V14 | Stable email receipt UUIDs, broker publication, delayed retries and failed-receipt state; preserves V13 content |
| V15 | Successful password-reset history and account/time index; preserves accounts and pending tokens; pre-upgrade completion counts are unavailable, so existing accounts start at zero |
| V16 | Encrypted recovery-email receipts, publication/retry/expiry indexes and lease ownership; preserves existing tokens/history; no retroactive email for pre-upgrade tokens |
| V17 | Password-change account/workspace/context audit and leased confirmation outbox; preserves populated recovery state and sessions; no retroactive notifications |
| V18 | Successful login history and cursor query indexes for login/password audit; preserves populated V17 audit/receipt/session data; no fabricated earlier logins |
| V19 | Forward migration removes audit-to-workspace foreign keys to avoid reversed workspace/account lock ordering; preserves captured workspace UUIDs, audit/receipt rows and account deletion cascades |

V18 is preserved at its original checksum `22842503`. If startup reports V18
checksum mismatch with locally resolved `94587119`, update to the restored V18
and new V19 files, then restart using your normal launch method. Flyway validates
the original V18 and applies V19 normally; do not repair the checksum, disable
validation, delete schema history or recreate the database for this mismatch.
V19 preserves existing history/receipt data. No environment setting is needed.

Back up before upgrades. V9 stops on duplicate exact workspace author/title pairs;
resolve intentionally without merging book IDs/documents/progress:

```sql
SELECT workspace_id, author, title, count(*) AS duplicates,
       array_agg(id ORDER BY id) AS book_ids
FROM books GROUP BY workspace_id, author, title HAVING count(*) > 1;
```

Export historical ISBN values before V9 if needed. Its event rewrite can be costly
on a large log. DDL/index changes may block writes; plan a maintenance window.
The public scope has independent exact author/title uniqueness. There are no undo
migrations: restore a coordinated database/file backup or apply a forward fix.
Never erase migration history or alter applied checksums to bypass validation.

Fresh seed books live under legacy workspace
`00000000-0000-0000-0000-000000000001`, with no automatically created owner.
An operator may explicitly assign a registered owner for legacy access:

```sql
UPDATE workspace_users
SET workspace_id = '00000000-0000-0000-0000-000000000001'
WHERE email = 'your-registered-owner@example.com';
```

The owner's original empty workspace remains. This operation changes tenancy and
requires an operator-reviewed migration/maintenance plan. New signups cannot access
legacy records automatically. Retain an existing PostgreSQL deployment on upgrade;
Compose creates a PostgreSQL 18 volume, not an automatic major-version conversion.

FREE/PRO are entitlements, not billing. Operators may set agreed limits directly:

```sql
UPDATE workspaces SET plan = 'PRO', book_limit = 10000
WHERE id = 'replace-with-workspace-uuid';
```

## Deployment, storage and backups

- Use HTTPS with a trusted reverse proxy. Configure forwarded scheme handling so
  `request.isSecure()` reflects HTTPS for OAuth cookies; accept forwarded headers
  only from trusted infrastructure. This repository does not configure that trust.
- Route `/api` and OAuth callback to the backend before SPA fallback. Forward
  Authorization, Range and response headers. CORS defaults do not enable cookies;
  use same-origin browser routing for Drive.
- Disable SSE buffering and permit five-minute streams; reconnects are expected.
  Stream authentication is checked at subscription, not continuously revalidated.
- Enforce gateway limits on signup/login/reset/upload/import. There is no general
  traffic rate limiter; book-request submissions and successful password recovery
  have monthly quotas. Super Admin raw PDF upload bypasses private file/page/
  storage quotas; set intentional infrastructure limits and monitor free disk.
- Keep PDFs outside public web roots. The Docker runtime uses UID 10001; mounts
  must be writable by that account. LOCAL replicas need the same protected volume.
- Back up PostgreSQL **and all retained PDF versions** together. Preserve encryption
  keys securely and exercise restoration. The database alone cannot restore PDFs.
- Monitor health, database/event/receipt growth, storage headroom and retry failures.
  Only aggregate health is exposed; LGTM is development tooling. Existing telemetry
  dependencies do not establish production dashboards/alerts or full trace coverage.

Inactive documents are retained. Failed uploads are normally cleaned up; a process
crash can leave `.part` or unreferenced UUID files. During stopped maintenance,
compare storage against **all** document rows, apply a grace period and remove only
confirmed orphans. Public deletion has a durable cleanup queue; direct SQL deletion
bypasses that workflow. Do not delete retained versions as apparent orphans.

Expired refresh rows can be purged with:

```sql
DELETE FROM refresh_tokens WHERE expires_at <= now();
```

Reset-token rows can be purged when expired and outside the issuance cooldown.
Successful reset history has no automatic retention; preserve at least the complete
current UTC month and the audit history required by your retention policy. Events and
progress receipts have no automatic retention; deleting them needs a documented
replay/idempotency policy. SMTP request/decision delivery is at least once; delayed retries allow healthy
mail to proceed and exhausted failures require operator review. Monitor both broker
queues and the database backlog; queue bounds do not bound PostgreSQL growth.
Recovery SMTP runs outside database transactions, with short claim/finalization
leases; its encrypted outbox is cleaned in bounded batches after expiry/replacement
when delivery is enabled. Paused workers also pause cleanup. Protect the stable
recovery encryption key with backups; rotation requires draining/retiring old receipts.
See [recovery operations](password-reset-email-queue.md) and [proposed operational improvements](../PROMPTS.md).

## Troubleshooting

| Symptom | Checks |
| --- | --- |
| Startup cannot resolve a secret | Export DATABASE_PASSWORD and JWT_SECRET; Maven does not read .env |
| Connection refused / schema validation fails | Check datasource host/port/credentials, PostgreSQL readiness and Flyway logs |
| Migration checksum mismatch | Restore the original migration; use a new migration for changes |
| V9 duplicate-pair failure | Run the preflight query and resolve editions explicitly |
| PDF 413 / 415 / 503 | Check multipart/private limits, MIME/extension/content, parser admission and disk availability |
| Cross-workspace 404 | Expected isolation; verify authenticated identity rather than adding workspace headers |
| Drive callback 400 | Check exact callback, same-browser binding cookie, proxy routing and state expiry |
| Drive/Picker unavailable | Check integration settings, grant status, project APIs and restricted key origins |
| Book request 429 | Check current UTC-month workspace history and Retry-After; review decisions do not restore slots |
| Request/decision mail pending | Check RabbitMQ health/queue declarations, SMTP readiness, due/failed receipts and broker alarms |
| Reset email 503 | Check SMTP readiness, PASSWORD_RESET_FROM and PASSWORD_RESET_EMAIL_ENCRYPTION_KEY; PASSWORD_RESET_URL is optional |
| Password change 204 but no confirmation | Check PASSWORD_CHANGE_EMAIL_ENABLED, sender/SMTP, confirmation outbox and broker/worker health; 204 does not prove delivery |
| Recovery 202 but no email | Check PASSWORD_RESET_EMAIL_ENABLED, reset outbox metadata, broker/consumer health, sender/key, token expiry/replacement, cooldown and monthly allowance; 202 does not prove delivery |
| Reset returns 429 / recovery email stops | Check PASSWORD_RESET_MONTHLY_LIMIT and current UTC-month password_reset_history for that account; read Retry-After; forgot-password remains generic 202 at exhaustion |
| SSE buffers or reconnects | Check proxy buffering/idle timeout, auth expiry and connection capacity |
| Tests fail at context startup | Use disposable PostgreSQL, valid ephemeral JWT settings and writable temp storage |

Do not publish secret-bearing logs, reset query strings, OAuth callbacks or rendered
Compose configuration. Live SMTP/Google, real client rendering/offline behavior and
production recovery require deployment-specific verification.
