# Booker SaaS API

A hosted, multi-tenant book catalogue built with Java 25, Spring Boot 4.1.1,
PostgreSQL and Flyway. Each signup creates an independent workspace and owner
account. Customers can create and search their books and receive live notifications.

This repository provides the API, not an Android client or web dashboard.
It includes a free plan with 100 books per workspace and operator-managed plan
limits. Payment checkout, recurring billing, email verification, password recovery,
team invitations and self-service upgrades are not implemented.

## Start with Docker

Install Docker with Compose, then run:

```sh
cp .env.example .env
# Edit .env: set DATABASE_PASSWORD and set JWT_SECRET using openssl rand -base64 32.
docker compose up --build -d
curl http://localhost:8080/actuator/health
```

The API is at `http://localhost:8080`. Interactive API documentation is at
`http://localhost:8080/swagger-ui/index.html`. PostgreSQL data persists in a named
volume. `docker compose down` stops the service while keeping data;
`docker compose down -v` deletes the database.

## Swagger / OpenAPI

Open `/swagger-ui/index.html` for interactive documentation, or download
`/v3/api-docs` for the OpenAPI JSON specification. Both are publicly accessible.

1. Expand **Workspaces → POST /api/auth/signup**, select **Try it out**, and create an account.
2. Call **Authentication → POST /api/auth/login**, then paste the returned `accessToken` into **Authorize → bearerAuth**.
3. Execute **GET /api/workspace** to see your plan and usage, then try the **Book Management** endpoints.

Schemas include signup validation, workspace response fields, book payloads and
application errors. Endpoint documentation describes tenant isolation, quotas,
and notification replay. Use the `curl -N` example below for SSE; Swagger UI is
not a live event viewer.

## Create your workspace

```sh
curl -i http://localhost:8080/api/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"workspaceName":"My Library","email":"owner@example.com","password":"replace-this-password"}'
```

A successful request returns HTTP 201 and the workspace ID, name, email and FREE
plan. Passwords must contain 12–64 characters and are stored as salted PBKDF2
hashes. Email addresses are normalized to lowercase and must be unique. Duplicate
signup returns 409 and does not leave an empty workspace behind.

Log in to obtain a JWT access token (15 minutes) and refresh token (7 days):

```sh
curl http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"owner@example.com","password":"replace-this-password"}'

# Set ACCESS_TOKEN and REFRESH_TOKEN to the returned values.
curl http://localhost:8080/api/workspace -H "Authorization: Bearer $ACCESS_TOKEN"
curl http://localhost:8080/api/auth/refresh \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}"
# Replace REFRESH_TOKEN with the new value from refresh before logging out.
curl -i http://localhost:8080/api/auth/logout \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH_TOKEN\"}"
```

Login and refresh return `accessToken`, `refreshToken`, `tokenType`, `expiresIn`
and `refreshExpiresIn` (lifetimes in seconds). Save both replacement tokens after
refresh: the old refresh token immediately becomes invalid. Refresh and logout
accept the refresh token in the JSON body, without an Authorization header.
Logout revokes that refresh token; existing access tokens remain valid until expiry.
Each login creates an independent session. Refresh tokens are stored only as SHA-256
hashes in PostgreSQL and survive application restarts. Use the same signing secret
across replicas; changing it invalidates all existing tokens. Expired database rows
can be periodically removed with `DELETE FROM refresh_tokens WHERE expires_at <= now()`.
Store tokens securely and use HTTPS for any non-local deployment.
HTTP Basic remains supported for existing clients.
The old shared `admin/admin` API account is no longer available.

## Global Public Library

Authenticated workspaces can read global books without duplicating them into their
private libraries. Super Admin creates/updates/deletes them at `/api/public-books`
and uploads PDFs. Public progress is shared by each workspace; private reading
progress stays account-specific. All public routes require authentication.

For initial setup, set `SUPER_ADMIN_EMAIL` and `SUPER_ADMIN_PASSWORD` (12–64
characters) using a dedicated email, then launch normally. Existing credentials
are not reset on restart; no signup request can choose this role. Existing
installations can omit these variables until provisioning. Remove bootstrap
secrets after setup. Log in through the same `/api/auth/login` endpoint.

Public upload streams a raw application/pdf body and bypasses normal private book,
file, page and storage quotas; private multipart limits are unchanged. Flyway V10
extends existing book/document infrastructure and adds workspace progress and
reliable file cleanup after public deletion. No frontend code is changed.

Public Library validation: all 92 backend tests passed with none skipped, including
a real HTTP public/private upload-limit check; the backend package build passed.

See [Public Library APIs, setup, schema and examples](docs/public-library.md) and
[complete 35-operation API inventory](api.md).

## Upload and read PDFs

Books can now have a private PDF document, uploaded from any authenticated client
or imported from a connected Google Drive account. Reading progress is per account;
existing book metadata and `completed` behavior remain unchanged.

The Angular client in `../booker-ui` includes upload/retry controls, a PDF reader,
resume position, progress summaries and Google Picker integration. The Android
client source has not been identified, so native client changes are still pending.

See [PDF reading APIs, schema, synchronization, storage and Google setup](docs/book-reading.md).
Compose persists PDFs in a separate `book-files` volume. Google Drive is disabled
until its OAuth settings and encryption key are supplied. Back up both the database
and the file volume. `docker compose down -v` removes both.

## Use the API

The following examples use the access token returned by login:

```sh
# View your workspace, plan, book_limit and books_used.
curl -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/api/workspace

# Create a book.
curl -i -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/api/books \
  -H 'Content-Type: application/json' \
  -d '{"title":"Effective Java","author":"Joshua Bloch","publishedDate":"2018","description":"Java best practices","completed":false}'

# List books.
curl -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/api/books

# Search by exact author, title, or both.
curl -H "Authorization: Bearer $ACCESS_TOKEN" --get http://localhost:8080/api/books \
  --data-urlencode 'author=Joshua Bloch' --data-urlencode 'title=Effective Java'

# Retrieve one book.
curl -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/api/books/1

# Stream your workspace's events; replay from the beginning.
curl -N -H "Authorization: Bearer $ACCESS_TOKEN" http://localhost:8080/api/books/events \
  -H 'Accept: text/event-stream' -H 'Last-Event-ID: 0'
```

Title and author are required (maximum 255
characters); publication date is a required string (maximum 20 characters),
and description is optional (maximum 5,000 characters). Creation returns 201
with a usable `Location` header. The exact, case-sensitive author/title pair is unique within a workspace; separate
workspaces may store the same pair. New workspaces start empty.

| Status | Meaning |
| --- | --- |
| 400 | Invalid JSON, fields, or event cursor |
| 401 | Missing or incorrect credentials |
| 403 | Workspace book limit reached, or endpoint access denied |
| 404 | Book ID does not exist in your workspace |
| 409 | Duplicate email or workspace author/title pair |
| 503 | Notification connection capacity reached |

Workspace identity comes from the authenticated account, never a client-supplied
workspace header. Search, numeric book-ID lookup and event replay are scoped to that identity.
Book limits are checked under a database row lock to serialize concurrent creates
for a workspace. Book insert and notification creation commit together.

For SSE, store the last event ID and send it as `Last-Event-ID` on reconnection.
Omit that header for future events only. IDs are global opaque strings and may
have gaps because other workspaces' events are filtered out. Connections close
after five minutes; reconnect with backoff. See [notification details](docs/book-notifications.md).

## Run from source and test

Install JDK 25 and Maven, or use the included `./mvnw` wrapper.

```sh
cp .env.example .env
# Edit the password, then start only the database.
docker compose up -d postgres
export DATABASE_URL=jdbc:postgresql://localhost:5432/android
export JWT_SECRET='your-base64-encoded-random-key'
export DATABASE_USERNAME=admin
export DATABASE_PASSWORD='the-password-you-set-in-.env'
./mvnw spring-boot:run
```

Compose reads `.env`; Maven and Java do not, so export configuration explicitly.
Flyway applies migrations on startup, and Hibernate validates the resulting schema.

Use a **dedicated test database** because integration tests create workspaces and
books. The optional event persistence test creates and removes its own schema.

```sh
DATABASE_URL=jdbc:postgresql://localhost:5432/booker_test \
JWT_SECRET="$JWT_SECRET" DATABASE_USERNAME=admin DATABASE_PASSWORD='test-database-password' \
BOOK_EVENTS_TEST_JDBC_URL='jdbc:postgresql://localhost:5432/booker_test?user=admin&password=test-database-password' \
./mvnw test

./mvnw -DskipTests package
java -jar target/android-0.0.1-SNAPSHOT.jar
```

Without `BOOK_EVENTS_TEST_JDBC_URL`, the low-level event persistence test is skipped.
The application-context and workspace integration tests still require PostgreSQL.

## Configuration and hosting

| Variable | Default / purpose |
| --- | --- |
| `PORT` | `8080` |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/android` |
| `DATABASE_USERNAME` | `admin` (local development) |
| `DATABASE_PASSWORD` | `admin` in source mode; required by Compose |
| `JWT_SECRET` | Required base64-encoded random key, at least 32 bytes (`openssl rand -base64 32`) |
| `JWT_ACCESS_TTL` | `PT15M`; ISO-8601 duration |
| `JWT_REFRESH_TTL` | `P7D`; must exceed access lifetime |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:4200`; comma-separated frontend origins |
| `SSE_MAX_CONNECTIONS` | `200` per application instance |
| `SSE_POLL_MILLIS` | `1000` |

Deploy the container behind an HTTPS reverse proxy, use a persistent PostgreSQL
database and inject credentials through your hosting platform's secret store.
Configure your actual frontend origin and disable reverse-proxy buffering for SSE.
Allow connections longer than five minutes. Health checks use `/actuator/health`.
Database backups and restore testing are the operator's responsibility.

Before public launch, configure gateway limits for signup, authentication and
requests. Signup currently has no email verification or abuse protection. Book
lists are unpaginated and the event log has no automatic retention policy.
Multiple app instances share database state, but SSE connection limits are per
instance. Plan limits are entitlements only; no customer is charged by this app.

An operator can provision a PRO entitlement directly in PostgreSQL after arranging
payment externally. Use the workspace ID returned at signup:

```sql
UPDATE workspaces SET plan = 'PRO', book_limit = 10000
WHERE id = 'replace-with-workspace-uuid';
```

There is deliberately no customer endpoint for granting paid entitlements.

## Author/title book identity upgrade

Books are identified by numeric `id`. Create requests and responses contain
`title`, `author`, `publishedDate`, `description`, and `completed`; detail lookup
is `GET /api/books/{bookId}`. Creation's Location points to that lookup.
The exact, case-sensitive `(workspace_id, author, title)` combination is unique.
The same author may have multiple titles and different authors may share a title.

This changes the previous book API contract. Update clients together with the
backend; Swagger metadata is version 2.0.0 and book-created payloads use
schemaVersion 2. Retained notification snapshots use the same field set as new
ones, while event IDs/order/cursors stay stable. See [all API contracts](api.md)
and [Android implementation prompts](ANDROID_PROMPTS.md).

Flyway V9 preserves numeric book IDs, document/progress references and existing
metadata, removes the previous identifier column, and adds the new unique
constraint. V1–V8 remain unchanged to preserve installed migration checksums.
Back up the database before upgrade. Check existing duplicate pairs first:

```sql
SELECT workspace_id, author, title, count(*) AS duplicates, array_agg(id ORDER BY id) AS book_ids
FROM books GROUP BY workspace_id, author, title HAVING count(*) > 1;
```

If duplicates exist, V9 stops transactionally with a clear message. Resolve them
explicitly (for example, distinguish edition titles) before rerunning; it never
silently merges/deletes books or their PDFs/progress. Back up/export the retired
identifier data if it is needed for audit. There is no down migration; restoring
removed values requires the pre-upgrade backup. Updating retained event payloads
can be substantial on a large log; schedule an appropriate maintenance window.

Identity-update validation: all 74 backend tests passed against an isolated
PostgreSQL 18 database with none skipped; all 13 Angular tests and the production
build passed (existing CSS budget warning).

## Upgrade an existing installation

Back up the existing database first. Flyway V4 preserves all existing books and
events under legacy workspace `00000000-0000-0000-0000-000000000001` without creating
an account for it. New customers cannot access that catalogue. To recover access,
register an owner account, then have the database operator assign that account:

```sql
UPDATE workspace_users
SET workspace_id = '00000000-0000-0000-0000-000000000001'
WHERE email = 'your-registered-owner@example.com';
```

That owner will access the legacy workspace on their next authenticated request.
The empty workspace originally created for that owner remains in the database.
For an existing PostgreSQL deployment, retain its current database/volume and point
`DATABASE_URL` at it; the supplied Compose configuration creates a new PostgreSQL
18 volume and does not upgrade an old database volume automatically.
