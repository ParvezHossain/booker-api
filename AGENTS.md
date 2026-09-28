# Book Upload and Reading Feature

## Current Functionality

- Authenticated users can see their book list.
- A book currently contains information such as:
    - Book name
    - Author
    - Completed/not completed
- Users can create books.

## General Implementation Requirements

- **Do NOT** rewrite the existing architecture unnecessarily.
- First inspect the existing:
    - Project structure
    - Entities
    - Repositories
    - Services
    - Controllers
    - Security configuration
    - Database migrations
    - API conventions
    - Error handling
    - Frontend architecture
- Reuse existing patterns wherever possible.
- Preserve backward compatibility with existing APIs.
- Do not introduce unnecessary libraries.
- Do not make assumptions about the existing code. Inspect it first.
- Implement the feature incrementally.
- After each major step, explain what was changed and why.
- Before modifying an existing class/entity, explain the proposed change.
- Follow existing naming conventions and coding style.

## Final System Requirements

The final system should support:

1. Uploading PDF books from Android.
2. Uploading PDF books from Angular/desktop.
3. Linking/importing books from Google Drive.
4. Storing book files securely.
5. Opening/reading the PDF.
6. Remembering the user's reading position.
7. Restoring the reader to the last position when the user opens the book again.
8. Showing reading progress, including:
    - Current page
    - Total pages
    - Pages read
    - Percentage completed
    - Last read time
9. Keeping reading progress per user.
10. Respecting workspace/user authorization.
11. Supporting large PDF files efficiently.
12. Avoiding storing the actual PDF binary directly in the relational database unless the existing architecture specifically requires it.

---

# Phase 1 — Understand the Existing Application

Before writing code:

## 1\. Inspect

- Spring Boot project structure
- Book entity
- User entity
- Workspace entity
- Relationships between User, Workspace, and Book
- Repositories
- Services
- Controllers
- DTOs
- Authentication/security
- JWT implementation
- Database migration system
- Exception handling
- API response conventions
- File upload conventions, if any
- Android architecture
- Angular architecture

## 2\. Determine

- Is Book owned directly by User or Workspace?
- How is authorization enforced?
- What database is being used?
- What migration framework is being used?
- Is there already object/file storage?
- Is there already multipart upload support?
- How are IDs generated?
- What API versioning convention exists?
- What Android networking library is used?
- What PDF reader library, if any, already exists?
- What Angular version and UI architecture are being used?

## 3\. Implementation Constraint

**Do NOT implement anything yet.**

## 4\. Phase 1 Deliverables

Produce:

- Architecture summary
- Relevant existing classes/files
- Proposed data model
- Proposed API
- Proposed storage architecture
- List of files that will likely change

**Wait for confirmation before proceeding to Phase 2.**

---

# Phase 2 — Design the Book File Data Model

Design the database model.

The existing `Book` entity should remain responsible for book metadata.

Introduce a separate concept for the physical book file/document.

## Example Data Model

### Book

- `id`
- `workspaceId`
- `name`
- `author`
- `completed`
- `createdAt`
- `updatedAt`
- `...`

### BookFile / BookDocument

- `id`
- `bookId`
- `storageProvider`
- `storageKey`
- `originalFileName`
- `mimeType`
- `fileSize`
- `pageCount`
- `checksum`
- `sourceType`
- `createdAt`
- `updatedAt`

## Possible `sourceType` Values

- `UPLOAD`
- `GOOGLE_DRIVE`

## Possible `storageProvider` Values

- `LOCAL`
- `S3`
- `OTHER_OBJECT_STORAGE`

Do not blindly use these names if the existing project has better conventions.

## Architectural Requirements

The database should store metadata and a reference to the file.

The PDF binary should preferably live in object/file storage.

The design must support:

- Large files
- Streaming downloads
- Future migration from local storage to S3-compatible storage
- Multiple files/versions if that becomes necessary later

Also consider whether a `Book` should have exactly one active document or multiple documents.

**Explain the decision before implementing it.**

---

# Phase 3 — Design Reading Progress

Create a reading progress model.

Reading progress should belong to a **USER + BOOK** combination.

## Example

### ReadingProgress

- `id`
- `userId`
- `bookId`
- `currentPage`
- `totalPages`
- `progressPercentage`
- `lastReadAt`
- `completed`
- `createdAt`
- `updatedAt`

Consider whether `totalPages` should be stored here or derived from `BookFile`.

## Required Behavior

Example:

### Book

> The Pragmatic Programmer

### PDF

144 pages

### User Opens

Page 1

### User Reads To

Page 93

### User Closes Application

Database should retain:

```
currentPage = 93
totalPages = 144
progressPercentage ≈ 64.58%
lastReadAt = timestamp
```

When the user opens the book again, the API should return page `93` as the resume position.

## Requirements

- Make progress updates idempotent.
- Avoid creating multiple progress records for the same user/book.
- Add an appropriate unique constraint.
- Protect the record using workspace/user authorization.
- Do not trust user-provided `workspaceId` if it can be derived from authentication.

---

# Phase 4 — Database Migrations

Create the required database migrations.

## Requirements

1. Add document/file metadata table.
2. Add reading progress table.
3. Add indexes for common queries.
4. Add a unique constraint for user \+ book reading progress.
5. Add foreign keys where appropriate.
6. Follow the existing migration framework and naming conventions.

Before implementation, show the migration design.

Then implement it.

## Verify

- Existing data still works.
- Existing `Book` records are unaffected.
- Migrations are reversible if the existing project convention supports rollback.

---

# Phase 5 — File Storage Abstraction

Create a storage abstraction.

For example:

```
FileStorageService
```

Conceptually support:

- Upload
- Download/stream
- Delete
- Exists
- Generate access URL, if appropriate

Do **NOT** couple business logic directly to local filesystem APIs.

Create an abstraction such as:

```
StorageService
```

with an implementation appropriate for the existing environment.

Initially it can use local storage if appropriate for development.

Design it so it can later support:

- AWS S3
- Cloudflare R2
- MinIO
- Another S3-compatible object store

## Security Requirements

- Never expose arbitrary filesystem paths through an API.
- Never allow users to access another user's/workspace's files.
- Use generated storage keys rather than trusting the uploaded filename.
- Validate MIME type and extension.
- Enforce maximum file size.
- Sanitize original filenames.
- Do not use the original filename as the storage key.

---

# Phase 6 — PDF Upload API

Implement an authenticated API for uploading a PDF to an existing Book.

Conceptually:

```
POST /api/books/{bookId}/document
```

### Request

`multipart/form-data`

```
file
```

## Requirements

1. Verify the authenticated user can access the Book.
2. Verify the Book belongs to the user's workspace according to the existing authorization model.
3. Validate:
    - PDF extension
    - MIME type
    - File size
    - File content where practical
4. Store the file using `StorageService`.
5. Determine PDF page count.
6. Store metadata.
7. Return document metadata.

## Example Response

```
{
  "bookId": "...",
  "documentId": "...",
  "fileName": "...",
  "fileSize": 12345678,
  "mimeType": "application/pdf",
  "pageCount": 144
}
```

Do not return the raw PDF in this response.

## Document Replacement

Consider whether uploading a new document should:

- Replace an existing document
- Create a new document version

Choose based on the existing product model and explain the decision.

---

# Phase 7 — PDF Download/Streaming API

Implement a secure endpoint for reading/downloading a book document.

Conceptually:

```
GET /api/books/{bookId}/document
```

or:

```
GET /api/books/{bookId}/document/content
```

## Requirements

1. Authenticate the user.
2. Verify access to the Book.
3. Verify the Book has a document.
4. Stream the PDF rather than loading the entire file into memory.
5. Set the correct `Content-Type`.
6. Support HTTP Range requests if practical/appropriate.
7. Support efficient PDF readers.
8. Never expose the underlying storage path.
9. Ensure one workspace/user cannot access another workspace's documents.

## Consider

- `Content-Disposition`
- Inline vs attachment
- Caching
- Range requests
- Large files
- Object storage signed URLs

If direct signed URLs are better for production, explain the tradeoff and implement according to the existing infrastructure.

---

# Phase 8 — Reading Progress API

Implement APIs for reading progress.

## Get Progress

Conceptually:

```
GET /api/books/{bookId}/reading-progress
```

### Response

```
{
  "bookId": "...",
  "currentPage": 93,
  "totalPages": 144,
  "pagesRead": 93,
  "progressPercentage": 64.58,
  "lastReadAt": "..."
}
```

## Update Progress

Conceptually:

```
PUT /api/books/{bookId}/reading-progress
```

### Request

```
{
  "currentPage": 93
}
```

## Requirements

1. Authenticate the user.
2. Verify book access.
3. Validate `currentPage`:
    - `>= 1`
    - `<= totalPages`
4. Create or update the user's progress.
5. Update `lastReadAt`.
6. Calculate percentage consistently on the backend.
7. Avoid trusting client-provided percentage.
8. Make repeated requests safe.
9. Handle the first-open case.

Consider whether page `0` should be allowed to represent "not started".

**Document the decision.**

---

# Phase 9 — Completion Logic

Define what "completed" means.

Possible behavior:

```
currentPage == totalPages
```

means reading progress is completed.

However, existing `Book.completed` may already represent something different.

**Do NOT automatically overwrite the existing `Book.completed` field until its current business meaning is understood.**

Determine whether:

- `Book.completed` should become derived from reading progress.
- `Book.completed` should remain manually controlled.
- Both concepts should exist separately.

Explain the choice and preserve existing behavior.

---

# Phase 10 — Google Drive Integration

Add Google Drive support.

The user should be able to connect/import a PDF from Google Drive.

## Important

Do **NOT** simply accept an arbitrary Google Drive URL and download it without authentication.

## Recommended Design Considerations

1. User connects Google Drive using OAuth 2.0.
2. Request only the minimum required Google Drive permissions.
3. Store OAuth credentials securely.
4. Store refresh tokens securely if required.
5. Allow the user to select a file.
6. Verify the selected file is accessible to the authenticated Google account.
7. Import/copy the PDF into our own storage.
8. Create `BookDocument` metadata.
9. Avoid depending on the user's Google Drive file remaining available for reading unless the product explicitly intends to support remote reading.

## Prefer Importing the PDF

Importing into our storage provides:

- Reading independent of Google Drive availability
- Progress controlled by our application
- Predictable reading performance
- Ability for users to disconnect Google Drive later

## Consider

- OAuth token expiration
- Revoked permissions
- Deleted Drive files
- Unsupported file types
- Google API rate limits

Do not expose Google OAuth secrets in Android or Angular source code.

Keep Google client secrets on the backend.

---

# Phase 11 — Android Upload

Implement Android support for:

1. Select PDF from device.
2. Upload PDF to backend.
3. Show upload progress.
4. Handle large files appropriately.
5. Handle network failure.
6. Retry safely where possible.
7. Display upload success/error.
8. Refresh Book details after successful upload.

Follow the existing Android architecture.

Do not introduce a new architecture unless necessary.

Reuse the existing:

- HTTP client
- Authentication mechanism
- Dependency injection
- ViewModel/state management
- Navigation
- UI conventions

If the application uses Retrofit/OkHttp, integrate with it rather than introducing another HTTP stack.

---

# Phase 12 — Android PDF Reader

Implement PDF reading in Android.

## Requirements

1. Open the selected book.
2. Load the PDF securely.
3. Display pages.
4. Start from the saved `currentPage`.
5. Track the current page.
6. Persist reading progress.
7. Avoid sending a network request on every tiny scroll/page movement.

## Progress Synchronization

Use a sensible synchronization strategy.

For example:

- Update local state immediately.
- Periodically sync with backend.
- Sync page changes after a debounce.
- Sync when reader closes/backgrounds.
- Sync when application goes into background.

## Handle

- Network unavailable
- App killed
- Rotation/configuration changes
- Large PDFs
- Loading errors

The reader should not reset to page 1 when the network is temporarily unavailable.

Consider maintaining local reading progress and synchronizing it with the backend.

---

# Phase 13 — Angular PDF Reader

Implement PDF reading in Angular.

## Requirements

1. User opens a book.
2. Frontend retrieves:
    - Document metadata
    - Reading progress
3. PDF reader opens.
4. Reader starts at `currentPage`.
5. Track page changes.
6. Persist progress.
7. Show:
    - Current page
    - Total pages
    - Percentage
    - Optionally last read time
8. Resume from saved position.

Use the existing Angular architecture and HTTP service patterns.

Do not expose storage credentials to the browser.

If the backend generates temporary signed URLs, make sure they are:

- Short-lived
- Scoped appropriately
- Inaccessible to unauthorized users

---

# Phase 14 — Book List UX

Update the existing book list.

## Books With Documents

Display something similar to:

```
The Pragmatic Programmer
by Andy Hunt

Reading:
93 / 144 pages
64.6%

Last read:
Today

Actions:
- Continue reading
- Open
- Upload/replace PDF
```

## Books Without Documents

Display something similar to:

```
The Pragmatic Programmer

Actions:
- Upload PDF
- Import from Google Drive
```

Do not clutter the existing book list.

Follow the existing UI design system.

---

# Phase 15 — Reading Progress UX

Add a progress indicator.

Examples:

```
93 / 144 pages
64.6% complete
```

or:

```
████████████░░░░░░ 64.6%
```

When reopening:

```
Continue from page 93
```

Potentially show:

```
Last read 2 hours ago
```

Use the existing date/time formatting conventions.

Do not calculate progress differently between Android, Angular, and backend.

The backend should be the source of truth for persisted progress.

---

# Phase 16 — Offline / Synchronization

Design what happens when the user reads while offline.

## Android

At minimum:

- Locally remember the current page.
- Allow reading if the PDF is already cached/available.
- Synchronize progress when connectivity returns.

## Angular

At minimum:

- Maintain local reader state while the tab is open.
- Synchronize when possible.

## Conflict Behavior

Define deterministic conflict behavior.

Example:

```
Server:
page 80

Android offline:
page 93

Later server:
page 85

Android:
page 93
```

A simple rule could be:

> Latest `lastReadAt` wins.

However, consider whether:

> Maximum page reached

is more appropriate for reading progress.

Document the selected strategy and implement it consistently.

---

# Phase 17 — Security Review

Perform a security review of the entire feature.

## Authentication

- Every document endpoint requires authentication.

## Authorization

- User can only access books they are authorized to access.
- Workspace isolation is enforced.

## File Upload

Check:

- Size limits
- MIME validation
- Extension validation
- PDF validation
- Malicious file handling
- Filename sanitization

## Storage

Check:

- No public filesystem paths
- No predictable storage keys
- No unauthorized object access

## Google Drive

Check:

- Secure OAuth
- Minimal scopes
- Secrets only on backend
- Encrypted/protected refresh tokens

## API

Check:

- Input validation
- Rate limiting considerations
- Error handling
- No sensitive information in errors

## PDF Serving

Check:

- Range requests cannot bypass authorization.
- Signed URLs cannot expose unrelated files.

---

# Phase 18 — Tests

Add tests.

## Backend

Test:

- Upload PDF successfully
- Reject non-PDF
- Reject oversized file
- Reject unauthorized book
- Reject unauthorized workspace
- Retrieve document metadata
- Stream PDF
- Reading progress creation
- Reading progress update
- Invalid page number
- Progress calculation
- Completion behavior
- Concurrent progress updates
- Google Drive import
- Invalid Google Drive file
- Expired/revoked Google OAuth

Integration tests should verify that a user from Workspace A cannot access documents belonging to Workspace B.

## Android

Test:

- Upload success
- Upload failure
- Retry
- Reader starts from saved page
- Progress persistence
- Offline progress
- Synchronization

## Angular

Test:

- Upload
- Reader initialization
- Resume page
- Progress update
- Error states

---

# Phase 19 — API Documentation

Document all new APIs.

For every endpoint document:

- HTTP method
- URL
- Authentication
- Authorization
- Request
- Response
- Error responses
- Examples

Update OpenAPI/Swagger if the project uses it.

---

# Phase 20 — Final Review

After implementation:

1. Review all changed files.
2. Look for unnecessary complexity.
3. Check backward compatibility.
4. Check database migrations.
5. Check authorization.
6. Check file security.
7. Check large-file handling.
8. Check Android behavior.
9. Check Angular behavior.
10. Check Google Drive OAuth.
11. Check reading progress synchronization.
12. Run all tests.

## Final Deliverables

Finally provide:

- Architecture summary
- Database schema
- API list
- Storage strategy
- Android implementation summary
- Angular implementation summary
- Google Drive flow
- Security considerations
- Tests added
- Environment variables/secrets required
- Deployment considerations
- Remaining TODOs

> **Important:** Do not claim something is implemented unless it actually exists in the codebase.