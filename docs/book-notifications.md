# Book notifications

Book snapshots contain numeric id, title, author, publishedDate, description and
completed. The author/title pair is unique per workspace. Flyway V9 upgrades
new and retained snapshots to schemaVersion 2 without changing event IDs/cursors.
Clients should accept that version and use GET /api/books/{bookId} for details.

Connect to `GET /api/books/events` with the same bearer access-token Authorization header (or legacy HTTP Basic header)
as the book API and `Accept: text/event-stream`. Use HTTPS in deployment.
Each authenticated workspace subscriber receives only events from its workspace,
including replayed book creations and public-book-request decisions. Register credentials using `/api/auth/signup`.

```text
id: 17
event: book.created
data: {"eventId":"17","type":"book.created","schemaVersion":2,"occurredAt":"2026-09-26T12:00:00.000Z","book":{"id":123,"title":"Effective Java","author":"Joshua Bloch","publishedDate":"2018-01-11","description":null,"completed":false}}

```

The first event is `ready`, containing an ID and `{}` data. Persist its ID too.
With no `Last-Event-ID` header, a connection starts at the current committed
cursor and receives future events. With that header, all later events are replayed
in order, then live delivery continues. Use `0` to replay all retained workspace events. Existing books from before the migration are not announced.
Invalid, negative or future cursors return HTTP 400. IDs are opaque decimal strings;
clients should not convert them to JavaScript numbers.

Persist the last successfully handled event ID and send it on every reconnect.
Handle duplicate event IDs idempotently: delivery is replayable, not exactly once.
The connection advertises a 3-second retry delay, sends heartbeat comments about
every 15 seconds, and expires after 5 minutes. Reconnect after normal closure,
network failure, or HTTP 503 with backoff and jitter. Refresh credentials for 401.

Angular needs a fetch-based SSE client capable of setting Authorization and
Last-Event-ID; native browser EventSource cannot set arbitrary headers.
Kotlin can use an SSE-capable HTTP client with the same headers. Never put
credentials in the URL. CORS currently allows `http://localhost:4200`; configure
the deployed Angular origin using `CORS_ALLOWED_ORIGINS`. GET/HEAD/POST/PUT/DELETE and Last-Event-ID are allowed.

This is live application delivery. It does not wake a suspended/closed Android app
or display operating-system notifications. Background push needs a separate
provider integration (for example FCM) and device registration. Reconnection
replays events missed while the app was offline.

Request reviews also emit `public-book-request.reviewed` with schemaVersion 1,
requestId, status, nullable bookId, message and occurredAt. See
[request decisions](public-library-requests.md) and [API payloads](../API.md#sse-payloads).
No document, reading-progress or import-completion SSE event exists. Unknown event
types should be ignored safely; this stream is not a mobile push service.
Authentication is checked at subscription time; an established stream can continue
until closure even if a password changes. Reconnection reauthenticates.

## Persistence and operations

Flyway V3 installs a PostgreSQL insert trigger that writes a snapshot event in the
same transaction as the book. Rollbacks and duplicate author/title pair failures create no
event. Inserts from any backend instance or direct SQL are covered; book updates
do not notify. Delivery never holds the book creation request open.

A transactional counter serializes event allocation until commit. Ordinary
sequence IDs alone would let a later transaction commit first and cause cursor
consumers to skip an earlier event. The counter deliberately serializes book
inserts, appropriate for this catalog's current architecture; revisit this with
a broker/CDC design if write throughput becomes high.

Each backend instance reads the shared event log, so reconnection can reach any
instance without sticky sessions. Polling defaults to 1 second, in batches of 100
per connection. Each instance shares one global cursor check per polling interval;
idle streams query their workspace history only on initial replay or when the
global cursor advances. Full replay batches drain immediately until caught up.
Virtual threads isolate client writes, and admission is bounded
to 200 streams per instance. Configure `books.notifications.poll-millis` and
`books.notifications.max-connections` as needed. A global change triggers a scoped
query for each connected client, so active fan-out still scales with connections.
Configure proxy write/idle
timeouts and disable response buffering/compression for this endpoint so slow
clients cannot retain resources indefinitely. Nginx buffering is also disabled
by the response header.

Events are retained indefinitely so reconnect cursors remain valid. Monitor table
size. Do not delete events without introducing a retention floor and an explicit
client resynchronization contract. New subscriptions require a healthy database;
existing streams close on database failure and recover through client replay.

Manual smoke test (credentials prompted by curl):

```sh
curl -N --user owner@example.com -H 'Accept: text/event-stream' http://localhost:8080/api/books/events
```

Create a book through the existing POST endpoint in another terminal. Expect one
`book.created` event. Reconnect with `-H 'Last-Event-ID: 0'` to verify replay.

## Tests

Run the notification unit and MVC tests without a database:

```sh
./mvnw -Dtest=BookEventStreamTest,BookNotificationControllerTest test
```

Run the PostgreSQL transaction/concurrency test against a disposable database:

```sh
BOOK_EVENTS_TEST_JDBC_URL='jdbc:postgresql://localhost:5432/test?user=test&password=test' \
  ./mvnw -Dtest=BookEventPersistenceTest test
```

The integration test creates and removes its own schema. It is skipped when the
environment variable is absent. The existing application context test also
requires a configured database.

Design references: [Spring MVC streaming and disconnect handling](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)
and [PostgreSQL transaction locks](https://www.postgresql.org/docs/17/explicit-locking.html).
