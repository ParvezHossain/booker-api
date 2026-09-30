# Booker SaaS: project lifecycle and client implementation brief

For the additive global Public Library, system-owner setup and workspace-shared
progress, see [docs/public-library.md](docs/public-library.md). The catalogue
contracts below refer to private workspaces; [api.md](api.md) contains all 35
business operations. No frontend implementation is part of this backend feature.

This document describes the backend **as implemented in this repository**. Use it
as a shared contract when building a new Angular or Android client, or redesigning
an existing one. Sections 1–9 are the common brief. Copy them together with the
Angular prompt in section 10 or the Android prompt in section 11.

The backend is a Java 25 / Spring Boot 4.1.1 API backed by PostgreSQL. It supports
independent book-library workspaces, owner signup, HTTP Basic authentication,
workspace usage, book creation/search and live book-created notifications.
It does not contain an Angular application or an Android application.

## 1. Product scope and boundaries

Each signup creates one empty workspace and one owner. The owner sees only that
workspace's books and events. The FREE plan permits 100 books. An operator can
assign PRO and a different book limit in the database; the API does not collect
payments or expose an upgrade endpoint. Read the actual limit from the workspace
response rather than hard-coding it in the client.

Build these screens:

- Signup: workspace name, email, password, client-only password confirmation.
- Sign in: email and password.
- Library: book list, exact author/title filters, loading/empty/error states.
- Create book: validated form and success/error feedback.
- Book details: retrieve by numeric book ID, display all returned fields.
- Workspace: name, plan, books used, book limit and remaining capacity.
- Connection feedback: live, reconnecting or offline status where useful.

The following features are **not supported by the current backend**: book editing,
deleting, toggling completion after creation, server pagination/sorting, fuzzy or
full-text search, team invitations, workspace switching, password reset/change,
email verification, billing/checkout, image uploads, and background push notifications.
JWT login/refresh/logout, PDF uploads/reading/progress and Google Drive OAuth/import
are supported; see api.md for the complete 23-operation contract. Do not build working-looking
controls that call invented endpoints. If a redesign needs these features, list
them as backend work separately.

`completed` is accepted when creating a book and displayed afterward. There is no
endpoint to change it. The exact, case-sensitive author/title pair is unique per workspace. Authors may have
multiple titles and different authors may share a title.

## 2. Backend startup, runtime and shutdown lifecycle

### Build and startup

1. Maven compiles and packages `AndroidApplication` and the Spring Boot application.
2. Spring loads environment-backed properties and creates the web application
   context, datasource, security, controller, service and repository beans.
3. Flyway validates and applies pending migrations in order:
   - V1 creates the original `books` schema (preserved historical migration).
   - V2 inserts sample books.
   - V3 creates the durable event log, transactional cursor and insert trigger.
   - V4 creates workspaces/users, assigns existing books/events to the legacy
     workspace, adds tenant ownership/uniqueness and updates the trigger (historical schema).
   - V5 adds refresh sessions; V6–V8 add PDFs, personal progress, Drive and indexes.
   - V9 removes the old book identifier field, adds exact workspace/author/title
     uniqueness, and upgrades notification snapshots to schemaVersion 2.
4. Hibernate validates the database schema; it does not create or update it.
5. The embedded server exposes the API, public Swagger documentation and health
   endpoint. A database or migration failure can prevent successful startup.

Fresh installations also run the historical seed migration. Seed books belong to
the legacy workspace after V4; new customers start with an empty catalogue.

### Request lifecycle

```text
Angular / Android / curl
  -> HTTP request
  -> Spring Security and browser CORS checks
  -> Basic credentials validated against workspace_users
  -> authenticated principal contains workspace identity
  -> controller parses input and validates request fields
  -> service applies workspace scope and business rules
  -> JPA repository / JdbcTemplate accesses PostgreSQL
  -> response DTO or error response
  -> client updates its state
```

Security rejects missing/incorrect credentials before protected controller logic.
For application exceptions, `GlobalExceptionHandler` returns the JSON error
structure in section 6. Security, proxy and network failures may have different
bodies, so clients must not assume every failure contains that structure.

### Signup transaction

`POST /api/auth/signup` validates the request, normalizes the email to lowercase,
creates a workspace UUID, and inserts the workspace and owner in one transaction.
The password is stored as a salted PBKDF2 hash. Duplicate email fails with 409;
the transaction rolls back both inserts. The response contains no password or token.

### Create-book transaction

The service locks the authenticated workspace row, checks for a duplicate
author/title pair and checks its quota, assigns the workspace internally and
inserts the book. A PostgreSQL trigger writes a snapshot `book.created` event in
the same transaction. Commit makes the book and event visible together; rollback
leaves neither. The database's workspace/author/title constraint also protects against
concurrent duplicate inserts. A global transactional event counter preserves
commit ordering and serializes event allocation across writers.

### Notification delivery and shutdown

Each accepted SSE subscription captures the authenticated workspace, reserves one
connection slot and starts a virtual-thread worker. The worker polls the durable
event log for that workspace in batches of up to 100. It sends events, heartbeat
comments, and releases capacity when the stream completes or disconnects.
Application shutdown interrupts workers and completes active emitters. Clients
must reconnect after a restart; the event log survives in PostgreSQL.

The project includes JobRunr and observability dependencies, but book notifications
use the database trigger and SSE worker, not a scheduled JobRunr job or mobile push
provider. Do not assume the presence of an app-specific job dashboard or push API.

## 3. Customer and client lifecycle

### First use

1. Show signup or sign in.
2. Validate signup fields locally, then send only the three API signup fields.
3. After 201, use the supplied credentials to call `GET /api/workspace`, or route
   to sign in. Signup does not establish a server session.
4. Treat successful workspace retrieval as successful sign in. Save workspace
   identity in client state, then load the library and start live updates.
5. Show the empty-library call to action for a new workspace.

### Sign in and authentication

Protected requests require:

```http
Authorization: Basic <base64 of email:password>
```

Use a suitable HTTP Basic encoder; Base64 is not encryption. The backend is
stateless and has no `/login`, `/logout`, access token or refresh token endpoint.
Sign in is a client flow that verifies credentials with `GET /api/workspace`.
A workspace ID, role, plan or tenant header supplied by a client does not grant
access to another workspace. Do not send one for authorization.

Keep credentials in memory by default. Do not log passwords or Authorization
headers, embed them in URLs, or put them in ordinary browser local/session storage.
If Android requires persistent sign-in, design an explicit opt-in credential store
using platform-backed protection; the current backend requires a reusable password
and does not provide a safer refresh token. Use HTTPS outside local development.

Attach credentials only to the configured backend origin. A sign-in request that
returns 401 should show an invalid-credentials message. A protected request that
later returns 401 should stop reconnect loops and return to authentication.
Do not interpret every 403 as invalid credentials: book creation uses 403 for quota.

### Normal use, logout and account changes

Load workspace usage and books. Allow exact author/title searches, book detail
navigation by numeric book ID and creation within the reported limit. After creation, merge
the returned book by its ID and refresh workspace usage. A subsequent SSE event
for the same book must not create a second row or count it twice.

Logout is local: cancel requests and SSE, clear credentials, workspace state,
books and event cursors, then show sign in. Cancel or ignore old in-flight responses
so they cannot repopulate the new account's screen. Any cache or stored cursor must
be scoped by backend origin and workspace/account. Never reuse one account's
cached data or cursor for another.

After a refresh or app restart, if credentials are not retained, request sign in
again. Reconnect and refetch authoritative data when returning online or foreground.
Offline data, if implemented, must be clearly marked stale and scoped to the account.
Do not silently queue writes or retry POST automatically: book creation has no idempotency
key. After an uncertain create result, search for that exact author/title pair to reconcile the outcome.

## 4. API contract

Local server base URL: `http://localhost:8080`.
Configure the client base URL per environment. For an Android emulator, use a host
address reachable from that emulator rather than assuming its `localhost` is the
host computer. For a physical device, use a reachable development host or HTTPS URL.
Development HTTP permissions must remain separate from production configuration.

| Method | Path | Authentication | Successful response |
| --- | --- | --- | --- |
| POST | `/api/auth/signup` | Public | 201 signup JSON |
| GET | `/api/workspace` | Basic | 200 workspace JSON |
| GET | `/api/books` | Basic | 200 array of books |
| GET | `/api/books?author=...&title=...` | Basic | 200 filtered array |
| GET | `/api/books/{bookId}` | Basic | 200 book JSON |
| POST | `/api/books` | Basic | 201 book JSON |
| GET | `/api/books/events` | Basic | 200 SSE stream |
| GET | `/actuator/health` | Public | Health status JSON |
| GET | `/v3/api-docs` | Public | OpenAPI JSON |
| GET | `/swagger-ui/index.html` | Public | Swagger UI |

This table covers catalogue endpoints. See api.md for all authentication, PDF,
reading progress and Google Drive endpoints. Use GET /api/books/{bookId} for details.

### Signup

Request:

```json
{
  "workspaceName": "My Library",
  "email": "owner@example.com",
  "password": "replace-this-password"
}
```

| Field | Validation |
| --- | --- |
| `workspaceName` | Required, nonblank, maximum 100 characters |
| `email` | Required, valid email, maximum 254 characters; normalized to lowercase |
| `password` | Required, nonblank, 12–64 characters; do not trim it |

Example 201 response (IDs are illustrative):

```json
{
  "workspaceId": "11111111-1111-4111-8111-111111111111",
  "workspaceName": "My Library",
  "email": "owner@example.com",
  "plan": "FREE"
}
```

### Current workspace

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "name": "My Library",
  "plan": "FREE",
  "book_limit": 100,
  "books_used": 1
}
```

Preserve the response's actual naming: signup uses `workspaceId` and
`workspaceName`; workspace uses `id`, `name`, `book_limit` and `books_used`.
Map these explicitly if the client uses a different internal naming convention.
Plans are currently `FREE` and `PRO`. The limit is an entitlement, not proof of
payment. Do not invent pricing, subscription renewal dates or a checkout flow.

### Book creation and book response

Create request:

```json
{
  "title": "Effective Java",
  "author": "Joshua Bloch",
  "publishedDate": "2018",
  "description": "Java best practices",
  "completed": false
}
```

| Field | Validation / meaning |
| --- | --- |
| `title` | Required, nonblank, maximum 255 characters |
| `author` | Required, nonblank, maximum 255 characters |
| `publishedDate` | Required string, maximum 20 characters; year or free-form date, not necessarily ISO |
| `description` | Optional/nullable string, maximum 5,000 characters |
| `completed` | Boolean; send explicitly on creation |

The response has the same fields plus numeric `id`:

```json
{
  "id": 51,
  "title": "Effective Java",
  "author": "Joshua Bloch",
  "publishedDate": "2018",
  "description": "Java best practices",
  "completed": false
}
```

A successful create sets `Location` to the book's numeric book-ID lookup URL. Cross-origin
browser code cannot currently read this header because CORS does not expose it;
use the returned `id` to navigate. Numeric book-ID lookup of a missing or another workspace's
book returns 404.

List and search return a bare array, not a page/envelope. Filters are exact matches;
both supplied means both must match. Omit empty filter parameters rather than
sending `author=` or `title=`. Use proper URL/query encoding. If client-side sorting
or pagination is useful, identify it as local presentation of the loaded array.
Do not send unsupported `page`, `size`, `sort` or general `q` parameters expecting
server behavior. Treat ordering as unspecified.

## 5. Live notifications: complete client lifecycle

Open `/api/books/events` with Basic authorization and
`Accept: text/event-stream`. This is SSE, not WebSocket, periodic REST polling or
an Android push notification service.

Example initial event:

```text
id: 17
event: ready
retry: 3000
data: {}

```

Example created-book event:

```text
id: 18
event: book.created
data: {"eventId":"18","type":"book.created","schemaVersion":2,"occurredAt":"2026-09-27T08:00:00.000Z","book":{"id":51,"title":"Effective Java","author":"Joshua Bloch","publishedDate":"2018","description":"Java best practices","completed":false}}

```

The payload's `occurredAt` is a UTC timestamp; `publishedDate` is unrelated and
remains an arbitrary date/year string. Keep `id`/`eventId` cursors as strings.
Global IDs may skip because other workspaces' events are filtered. Do not demand
consecutive numbers or parse cursor IDs into JavaScript numbers.

Rules:

1. No `Last-Event-ID` means future events only. `Last-Event-ID: 0` replays all
   retained events for the authenticated workspace. A saved ID replays later events.
2. Store the `ready` ID as well as successfully handled `book.created` IDs.
   Persist a book-event cursor only after applying the event successfully.
3. Parse SSE framing correctly: messages can span network chunks, data lines can
   be split, and comment heartbeats are not JSON. Ignore unknown event types
   safely and handle unsupported schema versions explicitly.
4. Upsert books by ID, applying the current list filter or refetching as needed.
   Do not append every event blindly. Refresh usage from `/api/workspace` rather
   than incrementing blindly during replay.
5. Heartbeats arrive about every 15 seconds. Streams expire after five minutes.
   Reconnect after normal EOF, server restarts or transient failures, sending
   the last processed cursor. Use bounded backoff with jitter and respect the
   advertised 3-second retry delay as the normal baseline.
6. For 401, stop and request authentication. For 503 or transient network failures,
   retry with backoff. For invalid-cursor 400, do not repeat the same bad cursor;
   clear that account's cursor and perform a fresh synchronization.
7. Close the old stream before opening a replacement. Cancel it on logout or
   account change. Keep a single active connection per client session.
8. Live events only describe inserts. Books that predate event logging have no
   creation event. SSE replay is not a substitute for listing books.

To avoid the gap between initial list loading and live subscription, establish
the stream first, wait for `ready`, and buffer incoming book events while fetching
the book list. Replace the local list with the HTTP snapshot, then merge buffered
events by ID and continue live merging. On reconnect, reconcile with a fresh
snapshot as needed. This also handles a book appearing in both the snapshot and
an event. Use cancellation/generation checks to avoid mixing results from two
accounts or two overlapping searches.

Angular must use a fetch-based streaming client or an SSE implementation that
supports headers; native browser `EventSource` cannot set Authorization.
Android needs an HTTP/SSE implementation that supports headers, cancellation and
streaming timeouts. Stop a foreground-only stream when the UI no longer needs it,
then reconnect/refetch on return. The backend cannot wake a suspended app or send
OS notifications. Such behavior requires a separate push integration.

## 6. Errors and UI behavior

Example application error (timestamp is server-local and carries no timezone):

```json
{
  "dateTime": "2026-09-27T14:00:00",
  "status": 409,
  "error": "Conflict",
  "message": "A book with this author and title already exists in your workspace",
  "path": "/api/books"
}
```

| Status | Client action |
| --- | --- |
| 400 | Show validation/request error; for SSE, repair cursor and resynchronize |
| 401 | Invalid credentials or authentication required; stop automatic auth retries |
| 403 | On create, show quota limit and refresh usage; elsewhere show access denied |
| 404 | Show book not found in this workspace |
| 409 | Show duplicate email/author-title pair as appropriate; preserve form input |
| 500 | Show a recoverable server error with retry where safe |
| 503 | SSE capacity/stopping: reconnect with backoff; health may also report unavailable |
| Network/timeout | Show offline/connection feedback; never assume a POST failed to commit |

Validation errors currently contain a combined `message`, not a structured
field-error map. Validate fields locally and display a form-level server message
when exact mapping is unavailable. Do not bind application logic to exact English
message text. Render errors as text, never HTML. Use a fallback for non-JSON or
empty error responses. Keep passwords out of error reporting and analytics.

## 7. Copy-and-paste curl walkthrough

Run the server first. These commands use Bash and prompt for the password on each
protected request, keeping it out of command arguments. Signup writes an example
password in the request below; replace it for your local test account.

```sh
export BOOKER_BASE_URL='http://localhost:8080'
export BOOKER_EMAIL='owner@example.com'

# 1. Check availability.
curl -i "$BOOKER_BASE_URL/actuator/health"

# 2. Sign up once. Repeating this email returns 409.
curl -i -X POST "$BOOKER_BASE_URL/api/auth/signup" \
  -H 'Content-Type: application/json' \
  --data '{"workspaceName":"My Library","email":"owner@example.com","password":"replace-this-password"}'

# 3. Sign in / verify credentials and obtain workspace usage.
curl -i --user "$BOOKER_EMAIL" "$BOOKER_BASE_URL/api/workspace"

# 4. List your books (initially []).
curl --user "$BOOKER_EMAIL" "$BOOKER_BASE_URL/api/books"

# 5. Create a book. Repeating the author/title pair in this workspace returns 409.
curl -i --user "$BOOKER_EMAIL" "$BOOKER_BASE_URL/api/books" \
  -H 'Content-Type: application/json' \
  --data '{"title":"Effective Java","author":"Joshua Bloch","publishedDate":"2018","description":"Java best practices","completed":false}'

# 6. Search by exact author and title. Omit either to filter by only one.
curl --user "$BOOKER_EMAIL" --get "$BOOKER_BASE_URL/api/books" \
  --data-urlencode 'author=Joshua Bloch' \
  --data-urlencode 'title=Effective Java'

# 7. Open details using the numeric database ID returned at creation.
curl -i --user "$BOOKER_EMAIL" "$BOOKER_BASE_URL/api/books/1"

# 8. Subscribe to future events. Keep this terminal open.
curl -N --user "$BOOKER_EMAIL" "$BOOKER_BASE_URL/api/books/events" \
  -H 'Accept: text/event-stream'
```

In a second terminal, create a different book with the same account to see a live
event. Export the two variables in that terminal too. Stop a stream with Ctrl+C.

```sh
# Replay every retained event in your workspace, then remain connected.
curl -N --user "$BOOKER_EMAIL" "$BOOKER_BASE_URL/api/books/events" \
  -H 'Accept: text/event-stream' -H 'Last-Event-ID: 0'

# Resume from an actual ID you received (replace 18 with your saved cursor).
curl -N --user "$BOOKER_EMAIL" "$BOOKER_BASE_URL/api/books/events" \
  -H 'Accept: text/event-stream' -H 'Last-Event-ID: 18'

# Negative cases: protected request without auth, missing book ID, invalid cursor.
curl -i "$BOOKER_BASE_URL/api/books"
curl -i --user "$BOOKER_EMAIL" "$BOOKER_BASE_URL/api/books/9223372036854775807"
curl -i --user "$BOOKER_EMAIL" "$BOOKER_BASE_URL/api/books/events" \
  -H 'Accept: text/event-stream' -H 'Last-Event-ID: invalid'

# Export the machine-readable contract.
curl -fsS "$BOOKER_BASE_URL/v3/api-docs" -o booker-openapi.json
```

For a tenant-isolation check, register a second email/workspace. Its list must be
empty; numeric lookup of the first workspace's book must return 404, even if it
creates its own book with the same author/title pair (which gets a distinct ID). Its SSE stream must not reveal the first workspace's events.

## 8. Local development, deployment and maintenance

Docker startup:

```sh
cp .env.example .env
# Edit .env: replace DATABASE_PASSWORD with a strong value.
docker compose up --build -d
```

Source startup (JDK 25; use Maven or the wrapper):

```sh
docker compose up -d postgres
export DATABASE_URL='jdbc:postgresql://localhost:5432/android'
export DATABASE_USERNAME='admin'
export DATABASE_PASSWORD='the-password-from-your-env-file'
./mvnw spring-boot:run
```

Compose reads `.env`; the Java/Maven command does not automatically read that file.
Swagger is at `http://localhost:8080/swagger-ui/index.html`: create a workspace,
click Authorize, and provide its owner email/password. Use curl for SSE observation.

| Setting | Meaning / default |
| --- | --- |
| `PORT` | HTTP port, 8080 |
| `DATABASE_URL` | PostgreSQL JDBC URL |
| `DATABASE_USERNAME`, `DATABASE_PASSWORD` | Database credentials, unrelated to customer credentials |
| `CORS_ALLOWED_ORIGINS` | Comma-separated browser origins; default `http://localhost:4200` |
| `SSE_MAX_CONNECTIONS` | Streams per backend instance; default 200 |
| `SSE_POLL_MILLIS` | Event polling interval; default 1000 |

Browser CORS permits GET/POST and Authorization, Cache-Control, Content-Type and
Last-Event-ID headers. Configure the actual Angular origin. Cross-origin cookie
sessions are not used. Native Android requests do not use browser CORS enforcement.
A browser CORS failure is not an incorrect-password response.

For hosting, use HTTPS, secret injection, a persistent database and backups. The
reverse proxy must allow streaming connections, disable SSE buffering and have
suitable idle timeouts. The API sends `Cache-Control: no-cache, no-store` and
`X-Accel-Buffering: no` on SSE responses. Add gateway abuse/rate controls before
opening public signup. Lists are unpaginated, events have no automatic retention,
and notification polling cost grows with active connections.

Multiple instances share books and events through PostgreSQL; SSE connection
limits are per instance. No sticky sessions are required for event replay.
Do not delete old events casually: there is no retention-floor/resync protocol.

Existing installations must back up their database before V4. Legacy records are
assigned to `00000000-0000-0000-0000-000000000001` with no automatic login account.
An operator can assign a registered owner to it as described in README.md. New
signups do not gain access to seeded or legacy records. Retain the existing
PostgreSQL deployment when upgrading; the supplied Compose volume does not perform
an automatic database major-version migration.

Stop containers with `docker compose down`; the volume remains.
`docker compose down -v` deletes the stored database. Never use it as a routine
production restart command.

## 9. Verification and acceptance checklist

Backend unit checks without PostgreSQL:

```sh
./mvnw -Dtest=BookEventStreamTest,BookNotificationControllerTest test
```

Full backend verification requires a dedicated, already-created test database:

```sh
DATABASE_URL='jdbc:postgresql://localhost:5432/booker_test' \
DATABASE_USERNAME='admin' DATABASE_PASSWORD='test-password' \
BOOK_EVENTS_TEST_JDBC_URL='jdbc:postgresql://localhost:5432/booker_test?user=admin&password=test-password' \
./mvnw verify
```

The event persistence test is skipped without `BOOK_EVENTS_TEST_JDBC_URL`.
Context/workspace tests still require PostgreSQL and create test records.

Client acceptance criteria:

- Signup, sign in, logout and reauthentication follow the Basic-auth contract.
- No requests target invented auth, edit, delete, billing or paging endpoints.
- Field validation matches the backend; book IDs are numeric and event cursors remain strings.
- Workspace usage maps snake_case response fields correctly.
- List, detail and searches work for empty and populated workspaces.
- Duplicate email/author-title pair, missing books and quota exhaustion have clear feedback.
- Two accounts cannot see each other's cached books, cursors or live events.
- No credentials are logged, placed in URLs or persisted in plain browser storage.
- SSE handles split frames, ready events, comments, reconnects and duplicate replay.
- Initial loading plus SSE produces no missing or duplicate books.
- Account change/logout cancels old streams and ignores stale responses.
- Offline/foreground return reconciles state without silently repeating creates.
- UI is usable with loading, empty, validation, offline and server-error states.
- Existing client conventions and platform accessibility needs are preserved.

## 10. Copyable Angular implementation / redesign prompt

Copy this prompt together with sections 1–9:

```text
Build or redesign the Angular client for Booker SaaS using the supplied backend
contract as the source of truth. Inspect the existing repository, its framework
version, routing, styling, state management and tests before changing code.
Preserve useful existing behavior and adapt to the installed stack. Implement the
work, not just a proposal, and document any backend blockers separately.

Implement signup, sign in, library list with exact author/title filters, create
book, ID-based details, workspace usage and local logout. Use a cohesive,
responsive, accessible interface with clear loading, empty, validation, error,
offline and reconnecting states. Do not invent a brand's pricing or billing flow.

Create typed request/response models matching the supplied JSON. Keep snake_case
workspace fields at the transport boundary or explicitly map them. Use a
configurable API base URL and a central API layer. Verify sign in through
GET /api/workspace. Scope the Basic-auth interceptor to the backend origin and
keep credentials in memory by default. Do not add JWT, refresh tokens or a
fictional login endpoint. Route guards support UX; backend authorization remains
the access control. Never log credentials or store them in local/session storage.

Implement authenticated SSE with a header-capable streaming client. The streaming
request must explicitly receive auth because Angular HTTP interceptors do not
automatically intercept a separate fetch call. Implement proper SSE framing,
ready cursor handling, replay, duplicate-safe upserts, cancellation and reconnect
backoff. Coordinate the initial list snapshot and buffered events. Scope caches
and cursors by backend/workspace, and discard stale work after account changes.
Do not use native EventSource with credentials in its URL.

Respect all unsupported-feature boundaries in the brief. Do not expose edit,
delete or post-creation completion toggles. Show the actual workspace limit and
refresh usage after creation/events. Handle uncertain POST outcomes without
blind retries. Do not assume cross-origin Location is readable.

Add focused tests for transport mappings, auth routing, validation, quota errors,
SSE parsing/replay and account isolation. Run applicable existing checks. Update
the client's setup guide with its API URL, CORS requirements, commands and known
limits. Report changes, verification results and any unimplemented requirements.
```

## 11. Copyable Android implementation / redesign prompt

Copy this prompt together with sections 1–9:

```text
Build or redesign the Android client for Booker SaaS using the supplied backend
contract as the source of truth. Inspect the existing application, language,
UI toolkit, networking, architecture and tests first. Follow existing project
conventions; do not force a framework migration as part of the redesign.
Implement the work and list backend blockers separately.

Implement signup, sign in, a library with exact author/title filters, book
creation, ID-based details, workspace usage and local logout. Provide a
cohesive accessible UI with loading, empty, field validation, duplicate/quota,
offline and reconnecting states. Preserve navigation state appropriately.

Use typed transport models, explicitly mapping book_limit and books_used. Treat
publication date and event cursor as strings, and book ID as Long; allow nullable description.
Configure the API base URL per build environment. Use a host address reachable
from the chosen emulator/device. Any development HTTP exception must be limited
to development builds; production uses HTTPS.

Authenticate protected API and SSE calls with Basic email/password. Validate
sign in via GET /api/workspace. There are no JWTs, refresh tokens or server login
and logout endpoints. Keep credentials in memory by default. If persistent
sign-in is required, make it opt-in and use platform-backed protection; document
that this backend requires storage of a reusable credential. Do not put secrets
in plain preferences, URLs, logs or crash reports. Attach credentials only to the
configured API origin.

Use a lifecycle-aware repository/state holder and a header-capable SSE client.
Support ready events, cursor persistence per account/backend, duplicate-safe
book merging, partial-frame parsing, heartbeats, cancellation and backoff on
reconnect. Coordinate initial snapshots with buffered events. Close unneeded
foreground streams and refetch/reconnect on foreground or network recovery.
Choose streaming timeouts that do not mistake an idle healthy stream for an
ordinary short HTTP request. Do not promise background OS push notifications.

Logout/account changes must clear the old account's state, cancel streams and
requests, and ignore stale completions. Any optional offline cache must be
account-scoped and marked stale. Avoid automatic POST retries; reconcile uncertain
creation results through numeric book-ID lookup. Refresh workspace usage rather than
counting replayed events. Respect unsupported edit/delete/billing/team features.

Add focused tests for transport mappings, authentication, validation, error states,
SSE replay and lifecycle/account isolation. Run applicable project checks and
update the client setup guide with API configuration, emulator/device connectivity,
run commands and limitations. Report changes and actual verification results.
```

## 12. Source map for future changes

| Concern | Source |
| --- | --- |
| Application entry | `src/main/java/com/parvez/android/AndroidApplication.java` |
| Authentication, CORS and access rules | `config/SecurityConfig.java` |
| Swagger metadata | `config/OpenApiConfig.java` |
| Signup, current workspace and owner lookup | `saas/WorkspaceAccounts.java` |
| Authenticated workspace identity | `saas/WorkspacePrincipal.java` |
| Book routes | `controller/BookController.java` |
| Book quota and business logic | `service/BookService.java` |
| Tenant-scoped database queries | `repository/BookRepository.java` |
| Book input/output and error contracts | `dto/BookRequest.java`, `dto/BookResponse.java`, `dto/ApiError.java` |
| Application error mapping | `exception/GlobalExceptionHandler.java` |
| SSE route, worker and event queries | `controller/BookNotificationController.java`, `notification/BookEventStream.java`, `notification/BookEventStore.java` |
| Schema and event trigger | `src/main/resources/db/migration/` |
| Runtime settings | `src/main/resources/application.properties` |
| Hosting | `Dockerfile`, `compose.yaml`, `.env.example` |

Java paths after the first row are relative to
`src/main/java/com/parvez/android/`. See [README.md](README.md) for the shorter
operator guide and [notification details](docs/book-notifications.md) for the SSE
operations contract. If a future implementation changes the API, update the code,
OpenAPI, README and this brief together.
