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

Title and author are taken from the original request; administrators provide the existing model's remaining metadata. No category/ISBN/language fields were invented. Publication date is required, at most 20 characters. Description is at most 5000 characters. Request title/author are required, at most 255 characters, and stripped of surrounding whitespace.

Errors: 400 invalid UUID, fields or multipart; 401 missing/invalid authentication; 403 missing workspace or Super Admin privilege; 404 missing request; 409 duplicate pending request, existing public book or already reviewed request; 413 configured multipart limit; 415 invalid PDF/type/extension; 503 storage unavailable. Rejection of a missing request returns 404. Invalid admin status filters return 400.

## Persistence and transactions

Flyway V12 adds public_library_book_requests, workspace/status indexes, a unique pending title/author pair per workspace, and public_request_emails. Existing public catalogue uniqueness remains authoritative at acceptance. Different workspaces can request the same title; once one is accepted, another acceptance encounters existing public-book duplicate rules. That request remains pending and can be rejected.

Requests begin PENDING. Review locks the request row, creates the public book through PublicBookService and validates/stores its PDF through BookDocumentService before marking ACCEPTED. Any failure rolls back database changes. Rollback synchronization removes an uploaded file if a later outer transaction fails; existing failed-upload cleanup handles parser/storage failures. Process crashes or cleanup failures still need the existing storage orphan reconciliation procedure. Reviewed requests cannot be reopened. Duplicate review calls return 409 rather than creating another book. Public book deletion preserves the request audit with a null bookId.

Multipart acceptance obeys spring.servlet.multipart.max-file-size/max-request-size, unlike the existing unlimited raw public PDF route. Configure proxy/container limits accordingly. This endpoint reuses public PDF content validation and does not add the private workspace quota.

## Notifications and email

Review inserts a durable workspace-scoped event in the existing book_events store. GET /api/books/events emits public-book-request.reviewed with eventId, type, schemaVersion=1, requestId, status, bookId, message and occurredAt. Existing book.created events keep their existing names and payloads. Clients should ignore unknown event types and persist SSE cursors; workspace request history remains available after disconnection.

The decision transaction also inserts an email receipt addressed to the authenticated submitter's registered email. The scheduled worker only sees committed receipts, uses the existing JavaMailSender and app.password-reset.from sender configuration, and deletes receipts after successful SMTP delivery. SMTP failure retains the receipt for retry and never rolls back the review. Missing SMTP/from configuration leaves receipts pending. Delivery is at least once: a crash after SMTP acceptance but before database commit can cause duplicate email. Multiple workers use FOR UPDATE SKIP LOCKED to avoid simultaneous receipt processing.

Required configuration: existing spring.mail.* and app.password-reset.from. Optional books.requests.email-poll-millis (default 30000). Existing SMTP timeout settings bound network waits. Monitor pending email receipts. No new libraries or frontend secrets are required.

## Verification

PublicLibraryRequestIntegrationTest covers authenticated submission, validation, identity derivation, workspace history isolation, admin authorization, rejection, acceptance, PDF rollback (including outer transaction file cleanup), concurrent reviews, duplicate submissions and invalid review transitions. PublicRequestEmailDeliveryTest covers SMTP triggering and retention on failure. OpenApiCoverageTest inventory increases from 38 to 43 operations. Existing public library tests continue to verify upload, reading, authorization and deletion.
