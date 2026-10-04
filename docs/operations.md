# Development and operations

[README](../README.md) owns the quick start. This guide owns detailed runtime
configuration, test isolation, schema upgrades and production operations.

## Configuration

`application.properties` maps environment variables to Spring settings. Compose
loads `.env`; Java/Maven require exported variables or an external configuration
provider. Copy `.env.example`, replace placeholders, and never commit secrets.
Environment-specific `.env.*` files are ignored; the safe example remains tracked.
Docker excludes secret files, stored PDFs and local tooling from its build context.

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
| `PASSWORD_RESET_FROM` | Provider-approved password-reset sender; fallback sender for request mail |
| `BOOK_REQUEST_EMAIL_FROM` | Request/decision sender; blank falls back to `PASSWORD_RESET_FROM`, then `SMTP_USERNAME`. Set explicitly if the SMTP username is not a provider-approved email address |
| `PASSWORD_RESET_URL` | HTTPS client landing page, no fragment; reset token appended as query parameter |
| `PASSWORD_RESET_TTL` | `PT30M`; positive, at most 24 hours |
| `BOOK_REQUEST_EMAIL_ENABLED` | true; pauses publisher/listener when false, leaving receipts pending |
| `RABBITMQ_HOST`, `RABBITMQ_PORT` | localhost / 5672; Compose uses rabbitmq / 5672 |
| `RABBITMQ_USERNAME`, `RABBITMQ_PASSWORD` | booker / required deployment secret; Compose requires nonempty password |
| `RABBITMQ_VHOST`, `RABBITMQ_SSL_ENABLED` | `/` / false locally; use isolated vhosts and TLS in production |
| `BOOK_REQUEST_EMAIL_*` delivery limits | See [queue settings](request-email-queue.md#configuration) for batch, capacity, retry and recovery defaults |
| `MANAGEMENT_OTLP_METRICS_EXPORT_URL` | Source default `http://localhost:4318/v1/metrics`; Compose supplies empty value |

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

Password reset requires mail transport, sender and HTTPS reset landing page.
Book-request and decision emails need transport and the same sender, but not a reset landing page.
SMTP operations have five-second connection/read/write timeouts. Missing reset
configuration returns 503; request review still commits with email pending.
See [passwords](password-management.md) and [requests](public-library-requests.md).

Provision Super Admin once using both bootstrap settings and a dedicated email.
Startup will not reset an existing admin password or promote an owner. Remove
bootstrap secrets after provisioning. New book requests resolve recipients from
persisted SUPER_ADMIN accounts and enqueue one email per administrator, independent
of removed bootstrap settings. No new notification-recipient variable is needed.
Without any provisioned admin, requests succeed with a warning but no admin receipt;
newly provisioned administrators do not receive past submission notifications. The normal login endpoint handles both roles;
Super Admin cannot read private customer libraries and has no workspace progress.

## RabbitMQ

Compose adds RabbitMQ 4.2 management with a persistent `rabbitmq-data` volume,
health-gated backend startup and loopback-only ports 5672/15672. Set a strong
`RABBITMQ_PASSWORD` before starting. Source mode must export the broker credentials;
Java does not read `.env`. Queue delivery is enabled by default and aggregate health
includes RabbitMQ. Broker outage does not roll back submitted requests: their
receipts remain in PostgreSQL, but health can report unavailable infrastructure.

Use a protected broker/vhost, TLS and production quorum-cluster capacity. All
replicas must share queue names/vhost/database. There is no direct SMTP fallback
when `BOOK_REQUEST_EMAIL_ENABLED=false`; it deliberately pauses delivery.
[Queue topology, saturation, retries, recovery and upgrade rules](request-email-queue.md)
are maintained in one feature guide.

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
keep the email queue disabled; `RequestEmailRabbitIntegrationTest` enables it with
its own schema/queue, excludes unrelated JobRunr setup for that isolated schema,
and uses real RabbitMQ with mocked SMTP. Broker tests are required, not optional. Check Surefire reports rather
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
  application rate limiter. Super Admin raw PDF upload bypasses private file/page/
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

Reset rows can be purged when expired and outside the issuance cooldown. Events and
progress receipts have no automatic retention; deleting them needs a documented
replay/idempotency policy. SMTP request/decision delivery is at least once; delayed retries allow healthy
mail to proceed and exhausted failures require operator review. Monitor both broker
queues and the database backlog; queue bounds do not bound PostgreSQL growth. Password reset SMTP is synchronous within its account
transaction. See [proposed operational improvements](../PROMPTS.md).

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
| Request/decision mail pending | Check RabbitMQ health/queue declarations, SMTP readiness, due/failed receipts and broker alarms |
| Reset email 503 | Check SMTP readiness, sender, reset URL where needed and pending receipts |
| SSE buffers or reconnects | Check proxy buffering/idle timeout, auth expiry and connection capacity |
| Tests fail at context startup | Use disposable PostgreSQL, valid ephemeral JWT settings and writable temp storage |

Do not publish secret-bearing logs, reset query strings, OAuth callbacks or rendered
Compose configuration. Live SMTP/Google, real client rendering/offline behavior and
production recovery require deployment-specific verification.
