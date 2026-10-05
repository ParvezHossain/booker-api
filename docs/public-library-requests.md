# Public library book requests

Registered workspace accounts request books with only title and authorName. Authentication supplies workspace ID and requester email. Requests belong to the workspace; all its accounts can see history. Super Admin reviews globally. Android and Angular UI are outside this backend feature.

## APIs

All endpoints require Bearer or legacy Basic authentication. Responses are JSON PublicLibraryBookRequest: id (UUID), title, authorName, workspaceId, requesterEmail, status, bookId (nullable numeric ID), reviewedBy, createdAt, reviewedAt. Timestamps are UTC instants. Existing ApiError format applies.

| Method | Path | Authorization | Request | Success |
| --- | --- | --- | --- | --- |
| POST | /api/public-book-requests | Workspace account | JSON title, authorName | 201 request |
| GET | /api/public-book-requests | Workspace account | None | 200 workspace request array |
| GET | /api/admin/public-book-requests | SUPER_ADMIN | Optional status=PENDING/ACCEPTED/REJECTED | 200 request array |
| POST | /api/admin/public-book-requests/{requestId}/accept | SUPER_ADMIN | Multipart file and JSON metadata | 200 accepted request |
| POST | /api/admin/public-book-requests/{requestId}/reject | SUPER_ADMIN | No body | 200 rejected request |

Example submission:

    {"title":"Clean Code","authorName":"Robert C. Martin"}

Accept with multipart part file (filename ending .pdf and application/pdf MIME) and part metadata containing JSON (either application/json or a plain form field):

    {"publishedDate":"2008","description":"Optional description","completed":false}

curl example (let curl generate the multipart Content-Type and boundary):

```bash
curl -X POST 'http://localhost:8080/api/admin/public-book-requests/REQUEST_ID/accept' \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Accept: application/json' \
  -F 'metadata={"publishedDate":"2008","description":"Optional description","completed":false}' \
  -F 'file=@book.pdf;type=application/pdf'
```

Malformed JSON, JSON null, missing publication date and oversized metadata fields return 400 before book creation or PDF storage.

Title and author are taken from the original request; administrators provide the existing model's remaining metadata. Publication date is required, at most 20 characters. Description is at most 5000 characters. Request title/author are required, at most 255 characters, and stripped of surrounding whitespace.

Errors: 400 invalid UUID, fields or multipart; 401 missing/invalid authentication; 403 missing workspace or Super Admin privilege; 404 missing request; 409 duplicate pending request, existing public book or already reviewed request; 429 workspace monthly submission quota exhausted (Retry-After header); 413 configured multipart limit; 415 invalid PDF/type/extension; 503 storage unavailable. Rejection of a missing request returns 404. Invalid admin status filters return 400.

## Monthly workspace request limit

Each registered workspace may create **10 requests per UTC calendar month**, shared
by all its accounts and applying equally to FREE and PRO. The window starts at
00:00 UTC on the first day of the month and resets at 00:00 UTC on the first day
of the next month; this is not a rolling 30-day window.

All successfully persisted requests count, including PENDING, ACCEPTED and REJECTED.
Reviewing a request does not restore allowance. Invalid/unauthenticated submissions,
existing-public-book conflicts, duplicate pending requests and rolled-back transactions
do not consume a slot. Existing requests in the current month count immediately
when this policy is deployed; workspaces already at or above 10 must wait for reset.

`POST /api/public-book-requests` returns 429 with the standard `ApiError` and a
`Retry-After` integer in seconds until the next UTC month. No request or email receipt
is created for a blocked submission. CORS exposes the header so Angular can read it.
There is no quota-usage endpoint, plan override, configuration switch or Super Admin
bypass for workspace submissions. Request history/review and email retries remain
available; RabbitMQ redelivery does not create another request or consume allowance.

`BookRequestRateLimiter` uses the existing `(workspace_id, created_at)` index and
locks the authenticated workspace row inside the submission's READ COMMITTED
transaction. It counts history after acquiring the lock; the indexed quota query
reads at most ten matching rows even for an existing workspace with a larger
historical backlog. The lock lasts until request/email commit or rollback.
This serializes concurrent submissions across
backend replicas without in-memory counters or a new database table. Database time
is read after locking and used for both the UTC window and the new `created_at`,
so a transaction waiting across midnight cannot count in one month and persist in
another. No Flyway migration or new environment variable is needed.

## Persistence and transactions

Flyway V12 adds public_library_book_requests, workspace/status indexes, a unique pending title/author pair per workspace, and public_request_emails. Existing public catalogue uniqueness remains authoritative at acceptance. Different workspaces can request the same title; once one is accepted, another acceptance encounters existing public-book duplicate rules. That request remains pending and can be rejected.

Flyway V13 extends the existing outbox with `email_type` (`SUBMISSION` or
`DECISION`), a subject and optional HTML body. Its primary key is now
`(request_id, email_type, recipient)`, so pending administrator notifications and
requester decisions coexist. Existing queued V12 decisions retain their recipient,
message and timestamp, receive the original subject, and remain plain-text emails.

Requests begin PENDING. Review locks the request row, creates the public book through PublicBookService and validates/stores its PDF through BookDocumentService before marking ACCEPTED. Any failure rolls back database changes. Rollback synchronization removes an uploaded file if a later outer transaction fails; existing failed-upload cleanup handles parser/storage failures. Process crashes or cleanup failures still need the existing storage orphan reconciliation procedure. Reviewed requests cannot be reopened. Duplicate review calls return 409 rather than creating another book. Public book deletion preserves the request audit with a null bookId.

Multipart acceptance obeys spring.servlet.multipart.max-file-size/max-request-size, unlike the existing unlimited raw public PDF route. Configure proxy/container limits accordingly. This endpoint reuses public PDF content validation and does not add the private workspace quota.

## Notifications and email

Review inserts a durable workspace-scoped event in the existing book_events store. GET /api/books/events emits public-book-request.reviewed with eventId, type, schemaVersion=1, requestId, status, bookId, message and occurredAt. Existing book.created events keep their existing names and payloads. Clients should ignore unknown event types and persist SSE cursors; workspace request history remains available after disconnection.

Successful submission inserts one `SUBMISSION` receipt for every persisted
`workspace_users` account with role `SUPER_ADMIN`, in the same transaction as the
request. Recipients are snapshotted at submission; bootstrap environment values
are not used for routing and may remain unset after provisioning. The notification
uses a professional HTML template with inline styles and a UTF-8 plain-text
alternative. It includes title, author, workspace name/ID, requester email,
request ID and submission time in UTC, followed by review instructions. All
submitted values are HTML-escaped; the fixed subject cannot contain user-supplied
header content. No frontend review URL is assumed.

Duplicate/invalid submissions do not enqueue another notification. Transaction
rollback removes both request and receipts. If no Super Admin account exists,
submission still succeeds, a warning is logged, and no administrator receipt is
created; provisioning an admin later does not backfill old requests. Provision
at least one Super Admin before accepting workspace requests.

The decision transaction also inserts a separate `DECISION` receipt addressed to
the authenticated submitter's registered email. Decision subjects/plain-text content
and submission HTML/plain-text templates remain unchanged.

V14 adds a stable receipt UUID and publication/retry timestamps. A bounded publisher
sends only due receipt IDs to RabbitMQ after commit and waits for confirmation;
recipient/content data remain in PostgreSQL. A durable quorum queue, single active
consumer and prefetch 1 serialize SMTP delivery across backend replicas. Only the
specific successful receipt is deleted, after SMTP acceptance; broker acknowledgement
follows database commit. Duplicate completed tokens are harmless, but a crash after
SMTP acceptance before commit can still duplicate email: delivery is at least once.

Failed sends use delayed exponential retries and are parked in PostgreSQL after the
configured maximum. They do not block healthy later mail. Unavailable/full RabbitMQ
retains unpublished receipts; confirmed but unfinished receipts become eligible for
recovery redispatch. Malformed/infrastructure-failed tokens are quarantined without
an immediate requeue loop. Missing sender settings leave mail pending.

Required configuration: existing `spring.mail.*` / `app.password-reset.from`, broker
credentials, and provisioned SUPER_ADMIN accounts for submission alerts. See the
[request email queue guide](request-email-queue.md) for queue limits, retry settings,
feature pause, deployment and operator recovery. Password-reset mail remains separate.

## Verification

PublicLibraryRequestIntegrationTest covers authenticated submission, validation, identity derivation, workspace history isolation, admin authorization, rejection, acceptance, PDF rollback (including outer transaction file cleanup), concurrent reviews, duplicate submissions, transactional administrator notifications, submission rollback, ten-request boundary, monthly reset, workspace/account/plan sharing, parallel submission enforcement and invalid review transitions. PublicRequestEmailDeliveryTest covers decision and administrator SMTP delivery, UTF-8 HTML/plain-text alternatives, precise receipt deletion, missing configuration and retention on failure. PublicRequestEmailTemplateTest verifies escaping and request details; BookReadingMigrationTest verifies preservation of queued emails during V13/V14 upgrades. RequestEmailPublisherTest verifies bounded batches and broker failures; RequestEmailRabbitIntegrationTest exercises real RabbitMQ backpressure, sequential delivery across two consumers, delayed retries, parked failures, duplicate tokens and quarantine. BookRequestRateLimiterTest covers post-lock time and leap-month/year boundaries. OpenApiCoverageTest verifies the submission 429/Retry-After schema, all 49 business operations and the Markdown route inventory. Existing public library tests continue to verify upload, reading, authorization and deletion.
