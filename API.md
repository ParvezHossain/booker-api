# API Documentation

Source-reviewed reference for **Booker SaaS API**. Contract metadata version: **2.1.0** (`OpenApiConfig`); routes use `/api` without a version segment. The inventory is checked against registered controller mappings by automated tests.

## Contents

- [1. Overview and base URL](#1-overview-and-base-url)
- [2. Authentication and authorization](#2-authentication-and-authorization)
- [3. Common conventions and errors](#3-common-conventions-and-errors)
- [4. Response schemas](#4-response-schemas)
- [5. Endpoint inventory](#5-endpoint-inventory)
- [6. Authentication and passwords](#6-authentication-and-passwords)
- [7. Workspace](#7-workspace)
- [8. Private books](#8-private-books)
- [9. Private PDF documents](#9-private-pdf-documents)
- [10. Private reading progress](#10-private-reading-progress)
- [11. Google Drive](#11-google-drive)
- [12. Workspace notifications](#12-workspace-notifications)
- [13. Public library](#13-public-library)
- [14. Public library book requests](#14-public-library-book-requests)
- [15. Operations and legacy mapping](#15-operations-and-legacy-mapping)
- [16. Client workflows and synchronization](#16-client-workflows-and-synchronization)
- [17. Configuration and maintenance](#17-configuration-and-maintenance)

## 1. Overview and base URL

The API provides workspace accounts, private book catalogues, a global authenticated public library, PDF upload/streaming, saved reading progress, Google Drive PDF import, public-book requests and workspace event notifications. It supports Android, Angular and other HTTP clients. This repository contains the Spring Boot backend; client feature completion is outside this reference.

Default local URL: `http://localhost:8080`. `server.port=${PORT:8080}` controls the port. No application context path or production hostname is configured; deployments use their externally configured scheme/host/proxy path. Use HTTPS outside local development.

Examples use these shell variables:

```sh
API_BASE='http://localhost:8080'
ACCESS_TOKEN='<access-token-returned-by-login>'
```

Substitute real resource IDs, OAuth values, UUIDs and selected PDF files. UUIDs, timestamps and sample records below illustrate valid shapes; they do not identify pre-provisioned resources. Super Admin examples require that account's token. Token/password placeholders are illustrative and do not authenticate.

OpenAPI JSON: `GET /v3/api-docs`; Swagger UI: `GET /swagger-ui/index.html` (also `/swagger-ui.html`). These documentation resources are public, separate from the business-operation count.

## 2. Authentication and authorization

Protected routes accept either:

```http
Authorization: Bearer <access-token>
```

or legacy HTTP Basic with a registered account's email/password (`curl --user owner@example.com`, which prompts for the password). Bearer and Basic are alternatives. Shared `admin/admin` API credentials are unsupported. Authentication is stateless; no session login or CSRF token is used.

Access and refresh tokens are HS256 JWTs with issuer `booker-saas` and distinct token types. Default access lifetime is 15 minutes; refresh lifetime is 7 days, configurable. Access decoding verifies expiry and current account credential version; authentication loads current account/workspace/role. Send only access tokens in Authorization. Refresh/logout authenticate via refreshToken in JSON; no Authorization header is required. Public endpoints can still reject an invalid Authorization header through the security filter.

Access labels used below:

| Label | Meaning |
| --- | --- |
| No | No API account authentication required; endpoint may require body credentials, reset token or bound OAuth state. |
| Authenticated | Any registered OWNER or SUPER_ADMIN account. |
| Workspace | Account with a workspace; OWNER by normal signup. SUPER_ADMIN has no workspace and receives 403. |
| Super Admin | Requires ROLE_SUPER_ADMIN. Ordinary workspace accounts receive 403. |
| Denied | Mapping exists but SecurityConfig denies HTTP access. |

Private books are scoped to the authenticated workspace. Private progress and Drive import jobs are scoped to the authenticated email; public-library progress is shared by the current workspace. Cross-workspace private IDs and wrong-library IDs return 404. No workspace/user selection header or request field is accepted by the documented DTOs. The term “public library” means global catalogue availability to authenticated accounts, not anonymous downloads.

Signup creates an OWNER in a FREE workspace. SUPER_ADMIN provisioning uses backend bootstrap settings; no signup/admin role assignment endpoint exists. Super Admin can manage/read public metadata/PDFs but cannot access private books or workspace progress.

## 3. Common conventions and errors

### Headers and representations

| Header | Applicability | Contract |
| --- | --- | --- |
| Authorization | Protected routes | Bearer access token or Basic credentials. |
| Content-Type | Requests with bodies | application/json for JSON; multipart/form-data with generated boundary for multipart; application/pdf for raw public upload. |
| Accept | Optional content negotiation | application/json for metadata; application/pdf for content; text/event-stream for SSE; text/html for OAuth callback. |
| Idempotency-Key | PDF uploads and Drive import start | Required UUID. Not required on request acceptance; progress uses body operationId instead. |
| Range | PDF content GET | Optional byte ranges; explicit HEAD ignores Range. |
| Last-Event-ID | SSE | Optional replay cursor; see endpoint. |
| Cookie | Google callback | Browser binding cookie issued by connect. Not general API authentication. |

Responses are direct objects/arrays without a common success envelope. IDs are numeric int64 for books and UUID strings for workspace/document/request/import/operation identifiers. JSON uses the declared DTO field names; workspace usage keys are `book_limit` and `books_used`. JSON examples use UTF-8. PDF Content-Disposition filenames explicitly use UTF-8; callback HTML declares UTF-8. No request/correlation ID API contract is declared.

`Instant` fields serialize as ISO-8601 timestamps with an offset/UTC `Z`; fractional seconds may be present. `ApiError.dateTime` is LocalDateTime **without an offset**. `publishedDate` is an arbitrary nonblank string up to 20 characters, not a parsed ISO date. Clients must tolerate nullable fields and use server progress calculations.

No endpoint implements page/size pagination or a client sort parameter. Lists return arrays with no total-count/total-pages envelope. Book lists support exact author/title filtering; admin book requests support exact status filtering. Request history is sorted by createdAt descending, summaries by numeric bookId ascending; book list order is not explicitly specified.

Browser CORS defaults to `http://localhost:4200`, configurable with comma-separated `CORS_ALLOWED_ORIGINS`. Allowed methods: GET, HEAD, POST, PUT, DELETE. Allowed headers: Authorization, Cache-Control, Content-Type, Last-Event-ID, Range, If-Range, Idempotency-Key. Exposed headers: Content-Length, Content-Range, Accept-Ranges, Content-Disposition, ETag, Retry-After. Location is not in that exposure list. CORS configuration does not enable credentials; browser-based Drive cookie handoff should use the same-origin API/proxy flow. Native Android HTTP requests are not governed by browser CORS.

### HTTP statuses used by this implementation

| Status | Verified use |
| --- | --- |
| 200 | Successful reads, login/refresh, saves, reviews, OAuth completion. |
| 201 | Workspace/book/request creation and PDF upload/replay; selected endpoints set Location. |
| 202 | Password reset email request and queued Drive import. |
| 204 | Logout, password change/reset, Drive disconnect, public deletion; no body. |
| 206 | PDF byte-range response. |
| 400 | DTO validation, malformed JSON, missing/invalid parameters/header/parts, invalid page/revision/state/cursor. |
| 401 | Missing/invalid authentication, invalid login/refresh/logout token. |
| 403 | Role/workspace restriction, book/storage quota or Google denial. |
| 404 | Missing/inaccessible book/PDF/import/request, wrong library. |
| 405 | Unsupported method on an accessible route; Allow identifies supported methods. |
| 406 | Accept requests an unsupported representation. |
| 409 | Duplicate/constraint, already-reviewed request, version/operation conflict, Drive connection required. |
| 413 | Private file limit or servlet multipart limit. |
| 415 | Unsupported request media type or unsupported/unsafe PDF. |
| 416 | Framework PDF range rejection. |
| 429 | Workspace monthly book-request quota or account monthly password-reset allowance exhausted; Retry-After gives seconds until the next UTC month. |
| 500 | Unhandled error with safe generic message. |
| 503 | Storage/parser/SSE/Google availability/configuration, unconfigured reset email, unhealthy health endpoint. |

### Application error format

`GlobalExceptionHandler` explicitly returns application/json with this ApiError:

```json
{
  "dateTime": "2026-10-01T12:00:00",
  "status": 400,
  "error": "Bad Request",
  "message": "title: Title is required",
  "path": "/api/books"
}
```

| Field | Type | Meaning |
| --- | --- | --- |
| dateTime | LocalDateTime | Server local timestamp; no timezone offset. |
| status | Integer | HTTP status. |
| error | String | HTTP reason phrase. |
| message | String | Safe explanation; validation field messages joined with comma and space. |
| path | String | Request URI, without query string. |

Malformed JSON returns `Invalid request body`; missing/type-invalid parameters/headers/parts return `Missing or invalid request parameter`; multipart oversize returns `PDF exceeds the upload limit`; unsupported request media type returns `Unsupported content type`; unsupported methods return 405 with `Allow`; unacceptable response media types return 406; unhandled exceptions return `An unexpected error occurred`. Database constraint violations return 409, with specific duplicate-book messages where recognized. There is no separate field-error object.

**Do not assume every error is ApiError.** Spring Security failures happen before controller advice and no custom JSON entry point/access-denied handler is configured. Their body is not fixed here; authentication challenge headers may be present. Range failures use framework behavior. HEAD responses have no body. Progress revision conflicts return a ReadingProgress object with HTTP 409; replaced-document/reused-operation conflicts return ApiError. Health errors return health objects. See each endpoint's errors plus these common rules. JSON-consuming endpoints may return 400/415 as described above; all controller operations may encounter the generic 500 handler.

## 4. Response schemas

Endpoint examples and field tables below reference these reusable schemas. Array responses contain objects of the named schema; no wrapper is added.

### BookResponse

| Field | Type | Description |
| --- | --- | --- |
| id | Integer (int64) | Numeric database ID. |
| title | String | Book title. |
| author | String | Author. |
| publishedDate | String | Publication date/year string. |
| description | String or null | Optional description. |
| completed | Boolean | Manual metadata status, independent of PDF completion. |

### DocumentResponse

| Field | Type | Description |
| --- | --- | --- |
| bookId | Integer (int64) | Owning book. |
| documentId | UUID | Immutable document version. |
| fileName | String | Sanitized original filename. |
| fileSize | Integer (int64) | Bytes stored. |
| mimeType | String | application/pdf. |
| pageCount | Integer | Validated page count. |
| checksum | String | SHA-256 checksum in hexadecimal. |
| sourceType | String | UPLOAD or GOOGLE_DRIVE. |
| active | Boolean | Whether this version is active at lookup time. |
| createdAt | Instant | Document creation timestamp. |

### ReadingProgress

| Field | Type | Description |
| --- | --- | --- |
| bookId | Integer (int64) | Book ID. |
| documentId | UUID | Active PDF version. |
| currentPage | Integer | Latest accepted resume position; 0 before first save. |
| totalPages | Integer | Active document page count. |
| pagesRead | Integer | Maximum page reached, not count of distinct visited pages. |
| progressPercentage | Decimal | 100 × pagesRead / totalPages; two decimals, HALF_UP rounding. |
| lastReadAt | Instant or null | Server timestamp of latest accepted new save; null before reading. |
| completed | Boolean | Maximum reached page equals totalPages; independent of rounded percentage. |
| resumePage | Integer | max(1, currentPage); one-based reader start page. |
| version | Integer (int64) | Server revision; initially 0. |

### Summary

| Field | Type | Description |
| --- | --- | --- |
| bookId | Integer (int64) | Book ID; results ordered ascending. |
| document | DocumentResponse or null | Active PDF metadata; null when absent. |
| progress | ReadingProgress or null | Progress for active PDF; null when document absent. |

### Tokens

| Field | Type | Description |
| --- | --- | --- |
| accessToken | String | Access JWT for protected endpoints. |
| refreshToken | String | Rotating refresh JWT. |
| tokenType | String | Bearer. |
| expiresIn | Integer (int64) | Access lifetime in seconds; default 900. |
| refreshExpiresIn | Integer (int64) | Refresh lifetime in seconds; default 604800. |

### SignupResponse

| Field | Type | Description |
| --- | --- | --- |
| workspaceId | UUID | Created workspace. |
| workspaceName | String | Trimmed workspace name. |
| email | String | Normalized owner email. |
| plan | String | FREE on signup. |

### WorkspaceResponse

| Field | Type | Description |
| --- | --- | --- |
| id | UUID | Current workspace. |
| name | String | Workspace name. |
| plan | String | FREE or operator-managed PRO. |
| book_limit | Integer | Configured metadata book quota. |
| books_used | Integer (int64) | Number of private book records; snake_case key. |

### PublicLibraryBookRequest

| Field | Type | Description |
| --- | --- | --- |
| id | UUID | Request identifier. |
| title | String | Requested title. |
| authorName | String | Requested author. |
| workspaceId | UUID | Requester workspace, derived by backend. |
| requesterEmail | String | Authenticated submitter email. |
| status | String | PENDING, ACCEPTED or REJECTED. |
| bookId | Integer (int64) or null | Created public book for accepted request. |
| reviewedBy | String or null | Reviewer email; null while pending. |
| createdAt | Instant | Submission time. |
| reviewedAt | Instant or null | Decision time; null while pending. |

### ImportStatus

| Field | Type | Description |
| --- | --- | --- |
| importId | UUID | Import operation; same as Idempotency-Key. |
| bookId | Integer (int64) | Private target book. |
| fileId | String | Selected Drive file. |
| status | String | PENDING, RUNNING, COMPLETED or FAILED. |
| documentId | UUID or null | Imported PDF on completion. |
| message | String or null | Safe failure/retry explanation when present. |

### Message

| Field | Type | Description |
| --- | --- | --- |
| message | String | Fixed generic response. |

### Connection

| Field | Type | Description |
| --- | --- | --- |
| connected | Boolean | Local connection exists and integration is enabled; not a live grant validity check. |

### AuthorizationURL

| Field | Type | Description |
| --- | --- | --- |
| authorizationUrl | String | Generated Google consent URL including PKCE and state. |

### Picker

| Field | Type | Description |
| --- | --- | --- |
| accessToken | String | Short-lived selected-file scoped Google token. |
| apiKey | String | Public restricted Picker key. |
| appId | String | Google project number. |

### Health

| Field | Type | Description |
| --- | --- | --- |
| status | String | Aggregate health status; component details disabled. |

## 5. Endpoint inventory

**43 business operations**, plus the password-token copy page, Actuator health and one denied legacy root mapping: **46 method/path entries** below. Implicit framework HEAD/OPTIONS handling, Swagger assets and framework error dispatch are not additional business APIs. Each entry has a detailed section.

| Method | Endpoint | Access | Purpose |
| --- | --- | --- | --- |
| POST | `/api/auth/signup` | No | Create workspace and owner |
| POST | `/api/auth/login` | No | Log in |
| POST | `/api/auth/refresh` | No | Rotate refresh token |
| POST | `/api/auth/logout` | No | Log out |
| POST | `/api/auth/change-password` | Authenticated | Change password |
| POST | `/api/auth/forgot-password` | No | Request password reset |
| POST | `/api/auth/reset-password` | No | Reset password |
| GET | `/password-reset-token` | No | Browser token copy helper (HTML) |
| GET | `/api/workspace` | Workspace | Get current workspace |
| GET | `/api/books` | Workspace | List or search private books |
| GET | `/api/books/{bookId}` | Workspace | Get private book |
| POST | `/api/books` | Workspace | Create private book |
| POST | `/api/books/{bookId}/document` | Workspace | Upload or replace private PDF |
| GET | `/api/books/{bookId}/document` | Workspace | Get private PDF metadata |
| GET | `/api/books/{bookId}/document/content` | Workspace | Read private PDF |
| HEAD | `/api/books/{bookId}/document/content` | Workspace | Inspect private PDF headers |
| GET | `/api/books/{bookId}/reading-progress` | Workspace | Get account-private reading progress |
| PUT | `/api/books/{bookId}/reading-progress` | Workspace | Save account-private reading progress |
| GET | `/api/books/reading-summaries` | Workspace | Batch account-private reading summaries |
| POST | `/api/integrations/google-drive/connect` | Authenticated | Start Google Drive connection |
| GET | `/api/integrations/google-drive/callback` | No | Complete Google OAuth callback |
| GET | `/api/integrations/google-drive/connection` | Authenticated | Check Google Drive connection |
| DELETE | `/api/integrations/google-drive/connection` | Authenticated | Disconnect Google Drive |
| GET | `/api/integrations/google-drive/picker` | Authenticated | Get Google Picker configuration |
| POST | `/api/books/{bookId}/document/imports/google-drive` | Workspace | Queue Google Drive PDF import |
| GET | `/api/books/{bookId}/document/imports/{importId}` | Workspace | Get Drive import status |
| GET | `/api/books/events` | Workspace | Subscribe to workspace events |
| GET | `/api/public-books/{bookId}/document` | Authenticated | Get public PDF metadata |
| GET | `/api/public-books/{bookId}/document/content` | Authenticated | Read public PDF |
| HEAD | `/api/public-books/{bookId}/document/content` | Authenticated | Inspect public PDF headers |
| GET | `/api/public-books/{bookId}/reading-progress` | Workspace | Get workspace-shared reading progress |
| PUT | `/api/public-books/{bookId}/reading-progress` | Workspace | Save workspace-shared reading progress |
| GET | `/api/public-books/reading-summaries` | Workspace | Batch workspace-shared reading summaries |
| GET | `/api/public-books` | Authenticated | List or search public books |
| GET | `/api/public-books/{bookId}` | Authenticated | Get public book |
| POST | `/api/public-books` | Super Admin | Create public book |
| PUT | `/api/public-books/{bookId}` | Super Admin | Replace public book metadata |
| DELETE | `/api/public-books/{bookId}` | Super Admin | Delete public book |
| POST | `/api/public-books/{bookId}/document` | Super Admin | Upload or replace public PDF |
| POST | `/api/public-book-requests` | Workspace | Request public library book |
| GET | `/api/public-book-requests` | Workspace | List workspace book requests |
| GET | `/api/admin/public-book-requests` | Super Admin | List administrator book requests |
| POST | `/api/admin/public-book-requests/{requestId}/accept` | Super Admin | Accept pending book request with PDF |
| POST | `/api/admin/public-book-requests/{requestId}/reject` | Super Admin | Reject pending book request |
| GET | `/actuator/health` | No | Check service health |
| GET | `/` | Denied | Legacy root mapping (denied) |

## 6. Authentication and passwords

### 6.1 Create workspace and owner

**HTTP request**

```http
POST /api/auth/signup
```

**Authentication / authorization:** No. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Content-Type | String | Yes | application/json |

**Request body and fields**

```json
{
  "workspaceName": "My Library",
  "email": "owner@example.com",
  "password": "replace-this-password"
}
```

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| workspaceName | String | Yes | Nonblank; maximum 100 characters; trimmed on registration. |
| email | String | Yes | Nonblank, email syntax, maximum 254 characters; trimmed and lowercased; globally unique. |
| password | String | Yes | Nonblank; 12–64 characters. |

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/auth/signup" \
  -H 'Content-Type: application/json' \
  -d '{"workspaceName":"My Library","email":"owner@example.com","password":"replace-this-password"}'
```

**Successful response**

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "workspaceId": "33333333-3333-4333-8333-333333333333",
  "workspaceName": "My Library",
  "email": "owner@example.com",
  "plan": "FREE"
}
```

**Response fields:** See [SignupResponse](#signupresponse).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 409 | Email already registered / database constraint; transaction rolls back. |

**Business / implementation notes**

Creates an empty FREE workspace with a 100-book limit. Does not return tokens; log in separately. Roles cannot be selected by signup.

### 6.2 Log in

**HTTP request**

```http
POST /api/auth/login
```

**Authentication / authorization:** No. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Content-Type | String | Yes | application/json |

**Request body and fields**

```json
{
  "email": "owner@example.com",
  "password": "replace-this-password"
}
```

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| email | String | Yes | Nonblank, email syntax, maximum 254 characters; normalized for lookup. |
| password | String | Yes | Nonblank; maximum 64 characters. |

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"owner@example.com","password":"replace-this-password"}'
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "accessToken": "<access-token>",
  "refreshToken": "<refresh-token>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshExpiresIn": 604800
}
```

**Response fields:** See [Tokens](#tokens).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Unknown email or incorrect password: Invalid credentials or refresh token. |

**Business / implementation notes**

OWNER and SUPER_ADMIN use the same login. Each login creates an independent refresh session. Cache-Control: no-store; Pragma: no-cache.

### 6.3 Rotate refresh token

**HTTP request**

```http
POST /api/auth/refresh
```

**Authentication / authorization:** No. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Content-Type | String | Yes | application/json |

**Request body and fields**

```json
{
  "refreshToken": "<refresh-token>"
}
```

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| refreshToken | String | Yes | Nonblank; maximum 4096 characters; latest refresh JWT, not access JWT. |

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/auth/refresh" \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"<refresh-token>"}'
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "accessToken": "<access-token>",
  "refreshToken": "<refresh-token>",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshExpiresIn": 604800
}
```

**Response fields:** See [Tokens](#tokens).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Expired, invalid, consumed or credential-version-invalid refresh token. |

**Business / implementation notes**

Consumes refresh token atomically once. Serialize refresh requests and replace both stored tokens together. Retrying a consumed refresh token fails. Cache-Control: no-store; Pragma: no-cache.

### 6.4 Log out

**HTTP request**

```http
POST /api/auth/logout
```

**Authentication / authorization:** No. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Content-Type | String | Yes | application/json |

**Request body and fields**

```json
{
  "refreshToken": "<refresh-token>"
}
```

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| refreshToken | String | Yes | Nonblank; maximum 4096 characters; latest refresh JWT, not access JWT. |

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/auth/logout" \
  -H 'Content-Type: application/json' \
  -d '{"refreshToken":"<refresh-token>"}'
```

**Successful response**

```http
HTTP/1.1 204 No Content
```

Response Body: None.

Response Fields: None.

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Malformed, expired or wrong token type. |

**Business / implementation notes**

Deletes only the supplied refresh session. A valid signed refresh token already removed returns 204. Existing access tokens remain valid until expiry unless credentials change. Clear client session/cache. Cache-Control: no-store.

### 6.5 Change password

**HTTP request**

```http
POST /api/auth/change-password
```

**Authentication / authorization:** Authenticated. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |
| Content-Type | String | Yes | application/json |

**Request body and fields**

```json
{
  "currentPassword": "replace-this-password",
  "newPassword": "another-long-password"
}
```

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| currentPassword | String | Yes | Nonblank; maximum 64 characters; must match current password. |
| newPassword | String | Yes | Nonblank; 12–64 characters. |

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/auth/change-password" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"currentPassword":"replace-this-password","newPassword":"another-long-password"}'
```

**Successful response**

```http
HTTP/1.1 204 No Content
```

Response Body: None.

Response Fields: None.

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). Current password is incorrect or fields fail validation. |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |

**Business / implementation notes**

Revokes all account access/refresh/reset tokens by credential-version increment and record deletion. Log in again. Works for workspace owners and Super Admin. Cache-Control: no-store.

### 6.6 Request password reset

**HTTP request**

```http
POST /api/auth/forgot-password
```

**Authentication / authorization:** No. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Content-Type | String | Yes | application/json |

**Request body and fields**

```json
{
  "email": "owner@example.com"
}
```

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| email | String | Yes | Nonblank, email syntax, maximum 254 characters. |

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/auth/forgot-password" \
  -H 'Content-Type: application/json' \
  -d '{"email":"owner@example.com"}'
```

**Successful response**

```http
HTTP/1.1 202 Accepted
Content-Type: application/json
```

```json
{
  "message": "If the account exists, a password reset email will be sent."
}
```

**Response fields:** See [Message](#message).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 503 | Password reset SMTP/sender or queued-token encryption key is not configured. |

**Business / implementation notes**

Same 202 body for unknown/known emails. Eligible requests commit token and encrypted email receipt before returning, with no SMTP or RabbitMQ I/O on the request thread. Background broker/provider failures do not change acceptance; bounded delayed retries retain the original expiry and skip expired/replaced/consumed tokens. At most one issuance per account per minute; default expiry 30 minutes. Token is emailed, never returned. With `PASSWORD_RESET_URL` blank, the email contains a token to copy into Swagger or an Android reset form. With an HTTPS reset URL configured, the email contains a link with a `token` query parameter. Email includes UTF-8 HTML and plain-text alternatives. `PASSWORD_RESET_TOKEN_PAGE_URL` adds a link to the backend copy helper, with the token and its stored expiry in a URL fragment (`#token=...&expiresAt=<epoch-milliseconds>`). Email shows the expiry in `PASSWORD_RESET_TIME_ZONE` (default `Asia/Dhaka`); the copy helper displays the deadline in the browser’s local timezone and a live countdown. SMTP, `PASSWORD_RESET_FROM` and a dedicated `PASSWORD_RESET_EMAIL_ENCRYPTION_KEY` remain required. A paused `PASSWORD_RESET_EMAIL_ENABLED=false` stores receipts without background delivery. See [queue workflow](docs/password-reset-email-queue.md). Cache-Control: no-store.

Accounts that have reached `PASSWORD_RESET_MONTHLY_LIMIT` successful resets in the
current UTC calendar month (default 3 per account, including Super Admin) receive
the same generic 202 without a new token or email. No 429 or allowance information
is exposed by forgot-password. Requesting/resending email does not consume allowance;
SMTP/sender or encryption-key configuration errors still return 503 for all accounts.

### 6.7 Reset password

**HTTP request**

```http
POST /api/auth/reset-password
```

**Authentication / authorization:** No. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Content-Type | String | Yes | application/json |

**Request body and fields**

```json
{
  "token": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  "newPassword": "another-long-password"
}
```

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| token | String | Yes | Matches `[A-Za-z0-9_-]{43}`; valid unexpired single-use emailed token. |
| newPassword | String | Yes | Nonblank; 12–64 characters. |

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/auth/reset-password" \
  -H 'Content-Type: application/json' \
  -d '{"token":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","newPassword":"another-long-password"}'
```

**Successful response**

```http
HTTP/1.1 204 No Content
```

Response Body: None.

Response Fields: None.

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). Invalid, expired or already-used reset token; invalid fields. |
| 429 | Valid token but account has reached the monthly successful-reset limit; Retry-After gives seconds until the next UTC month. |

**Business / implementation notes**

Example token illustrates syntax only: use the actual emailed token. Single-use reset revokes all account sessions and reset tokens. Log in again. Cache-Control: no-store.

`PASSWORD_RESET_MONTHLY_LIMIT` is a positive integer, default **3 successful resets
per account per UTC calendar month**, persisted across restarts and replicas.
Only committed successful resets count; authenticated change-password, email
issuance, invalid/expired/used tokens, SMTP failures and rollback do not. The account
lock serializes checks and the successful history insert with password/session
replacement. Month boundaries use post-lock database time in UTC; display timezones
do not change the quota. Invalid/expired tokens remain 400 even at the limit.

On 429, the response is `ApiError` with message
`You can reset your password at most 3 times per UTC calendar month` (the number
reflects configuration) and integer `Retry-After` seconds, rounded up to the next
UTC month. Password, sessions and the submitted valid token remain unchanged.
Clients must show the wait and avoid automatic retries; request a fresh email after
the allowance renews if the token has expired. Existing accounts start at zero on
the V15 upgrade because earlier reset completion history was not recorded.

### 6.8 Password reset token copy page

Public `GET /password-reset-token` returns **200**, `Content-Type: text/html;charset=UTF-8`,
`Cache-Control: no-store`, `Referrer-Policy: no-referrer`, a nonce-based restrictive
Content-Security-Policy and `Permissions-Policy: clipboard-write=(self)`.
It serves a browser helper, not a new token-retrieval or password-reset API.
The browser reads `#token=<43-character-token>&expiresAt=<epoch-milliseconds>`, removes the fragment from history,
and copies the token only after a button click. Fragments are not sent to the backend;
query tokens are not read or reflected. Missing/malformed tokens disable copying.
The page formats the absolute deadline in the browser’s local timezone and locale,
including its timezone label. It counts down from the stored deadline, updates when a background
tab becomes visible, and disables copying at zero. This display depends on the browser
clock and is advisory; database expiry and single-use enforcement stay in the reset API.
Old links with no usable expiry metadata still allow copying and show expiry as
unavailable. The page never queries validity, consumes a token or changes a password. If clipboard
access is unavailable, it offers selected text for manual copying. No account login
is needed. The existing POST reset endpoint still performs all validation.

## 7. Workspace

### 7.1 Get current workspace

**HTTP request**

```http
GET /api/workspace
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/workspace" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "id": "33333333-3333-4333-8333-333333333333",
  "name": "My Library",
  "plan": "FREE",
  "book_limit": 100,
  "books_used": 1
}
```

**Response fields:** See [WorkspaceResponse](#workspaceresponse).

**Error responses**

| Status | Condition |
| --- | --- |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |

**Business / implementation notes**

Workspace is derived from authenticated account. Super Admin has no workspace. PRO entitlements/limits are operator-managed; no payments or upgrade endpoint.

## 8. Private books

### 8.1 List or search private books

**HTTP request**

```http
GET /api/books
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Query parameters**

| Parameter | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| author | String | No | Exact author match; combined with title using AND. | Joshua Bloch |
| title | String | No | Exact title match. | Effective Java |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/books?author=Joshua+Bloch&title=Effective+Java" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
[
  {
    "id": 1,
    "title": "Effective Java",
    "author": "Joshua Bloch",
    "publishedDate": "2018",
    "description": "Java best practices",
    "completed": false
  }
]
```

**Response fields:** See [BookResponse](#bookresponse). Response is an unpaginated array.

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |

**Business / implementation notes**

Unpaginated array; empty result is []. No supported page, size or sort parameters. Result order is not explicitly specified. Filters are exact and case-sensitive, not substring search.

### 8.2 Get private book

**HTTP request**

```http
GET /api/books/{bookId}
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/books/1" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "id": 1,
  "title": "Effective Java",
  "author": "Joshua Bloch",
  "publishedDate": "2018",
  "description": "Java best practices",
  "completed": false
}
```

**Response fields:** See [BookResponse](#bookresponse).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |
| 404 | Book not found in current workspace. |

**Business / implementation notes**

Identity and authorization are derived from the authenticated account; no client workspace selector is used.

### 8.3 Create private book

**HTTP request**

```http
POST /api/books
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |
| Content-Type | String | Yes | application/json |

**Request body and fields**

```json
{
  "title": "Effective Java",
  "author": "Joshua Bloch",
  "publishedDate": "2018",
  "description": "Java best practices",
  "completed": false
}
```

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| title | String | Yes | Nonblank; maximum 255 characters. |
| author | String | Yes | Nonblank; maximum 255 characters. |
| publishedDate | String | Yes | Nonblank; maximum 20 characters. String, not validated as a date. |
| description | String or null | No | Maximum 5000 characters. |
| completed | Boolean | No | Primitive boolean; omitted value defaults to false. Manual metadata status. |

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/books" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Effective Java","author":"Joshua Bloch","publishedDate":"2018","description":"Java best practices","completed":false}'
```

**Successful response**

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "id": 1,
  "title": "Effective Java",
  "author": "Joshua Bloch",
  "publishedDate": "2018",
  "description": "Java best practices",
  "completed": false
}
```

**Response fields:** See [BookResponse](#bookresponse).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. Workspace book limit reached. |
| 409 | Exact author/title pair already exists in workspace. |

**Business / implementation notes**

Location identifies /api/books/{id}. Quota check serialized by workspace row lock. Book and book.created event commit together. No private metadata update/delete route is exposed.

## 9. Private PDF documents

### 9.1 Upload or replace private PDF

**HTTP request**

```http
POST /api/books/{bookId}/document
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |
| Content-Type | String | Yes | multipart/form-data |
| Idempotency-Key | UUID | Yes | Persist one UUID per upload operation; reuse for retry. |

**Request body and fields**

Multipart: file=@effective-java.pdf;type=application/pdf.

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| file | Binary multipart part | Yes | Part name `file`; filename ending .pdf; part Content-Type application/pdf; nonempty validated PDF. |

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/books/1/document" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Idempotency-Key: 22222222-2222-4222-8222-222222222222' \
  -F 'file=@effective-java.pdf;type=application/pdf'
```

**Successful response**

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "bookId": 1,
  "documentId": "11111111-1111-4111-8111-111111111111",
  "fileName": "effective-java.pdf",
  "fileSize": 100000,
  "mimeType": "application/pdf",
  "pageCount": 144,
  "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "sourceType": "UPLOAD",
  "active": true,
  "createdAt": "2026-10-01T06:00:00Z"
}
```

**Response fields:** See [DocumentResponse](#documentresponse).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). Missing/invalid filename or empty PDF. |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. Workspace storage budget exhausted. |
| 404 | Private book not found in the authenticated workspace. |
| 413 | File/multipart size limit exceeded. |
| 415 | Invalid extension/MIME or unsafe, encrypted or invalid PDF. |
| 503 | Storage unavailable or validator busy. |

**Business / implementation notes**

Creates an immutable version and switches active PDF only after successful validation. Old versions remain and count toward storage quota. Retry lookup uses book + account + UUID and returns original metadata without comparing the new bytes. Reuse only for the same intended upload; replayed version may now be inactive. Refetch metadata. Location points to document metadata; Cache-Control: no-store.

### 9.2 Get private PDF metadata

**HTTP request**

```http
GET /api/books/{bookId}/document
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/books/1/document" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "bookId": 1,
  "documentId": "11111111-1111-4111-8111-111111111111",
  "fileName": "effective-java.pdf",
  "fileSize": 100000,
  "mimeType": "application/pdf",
  "pageCount": 144,
  "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "sourceType": "UPLOAD",
  "active": true,
  "createdAt": "2026-10-01T06:00:00Z"
}
```

**Response fields:** See [DocumentResponse](#documentresponse).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |
| 404 | Book or active PDF not found in accessible library. |

**Business / implementation notes**

Only active version exposed. No storage path/provider/key in response. Cache-Control: no-store.

### 9.3 Read private PDF

**HTTP request**

```http
GET /api/books/{bookId}/document/content
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Query parameters**

| Parameter | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| download | Boolean | No | Default false; true selects attachment instead of inline. | false |
| documentId | UUID | No | Must equal active version; mismatch returns 409. Does not select historical version. | 11111111-1111-4111-8111-111111111111 |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |
| Range | String | No | Byte range, e.g. bytes=0-65535, bytes=65536-, or bytes=-1024. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/books/1/document/content?download=false&documentId=11111111-1111-4111-8111-111111111111" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Range: bytes=0-65535'
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/pdf
```

Response Body: Binary PDF bytes (200) or requested PDF byte ranges (206); not JSON. The cURL example requests a range.

Response Fields: None (binary representation).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |
| 404 | Book or active PDF not found. |
| 409 | Pinned document was replaced. |
| 416 | Invalid/unsatisfiable byte range; framework response, not guaranteed ApiError. |
| 503 | Document storage/content unavailable. |

**Business / implementation notes**

200 full PDF or 206 byte range with Content-Range. Streams Resource through Spring MVC. Headers: Content-Type application/pdf, Accept-Ranges bytes, ETag quoted document UUID, Content-Disposition inline/attachment with UTF-8 filename, Cache-Control no-store, X-Content-Type-Options nosniff. Every range request requires authentication. No signed URL returned. No application-specific If-Range processing is implemented; do not rely on it instead of documentId pinning.

### 9.4 Inspect private PDF headers

**HTTP request**

```http
HEAD /api/books/{bookId}/document/content
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Query parameters**

| Parameter | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| download | Boolean | No | Default false; true selects attachment instead of inline. | false |
| documentId | UUID | No | Must equal active version; mismatch returns 409. Does not select historical version. | 11111111-1111-4111-8111-111111111111 |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -I "${API_BASE}/api/books/1/document/content?download=false&documentId=11111111-1111-4111-8111-111111111111" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/pdf
```

Response Body: None.

Response Fields: None.

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |
| 404 | Book or active PDF not found. |
| 409 | Pinned document was replaced. |
| 503 | Document storage/content unavailable. |

**Business / implementation notes**

Same authorization and version checks as GET. Returns complete 64-bit Content-Length and PDF headers without opening a byte stream. Ignores Range. Response Body: None, including HEAD errors.

## 10. Private reading progress

### 10.1 Get account-private reading progress

**HTTP request**

```http
GET /api/books/{bookId}/reading-progress
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/books/1/reading-progress" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "bookId": 1,
  "documentId": "11111111-1111-4111-8111-111111111111",
  "currentPage": 93,
  "totalPages": 144,
  "pagesRead": 93,
  "progressPercentage": 64.58,
  "lastReadAt": "2026-10-01T06:00:00Z",
  "completed": false,
  "resumePage": 93,
  "version": 1
}
```

**Response fields:** See [ReadingProgress](#readingprogress).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |
| 404 | Book or active PDF not found. |

**Business / implementation notes**

First open returns currentPage=0, pagesRead=0, progressPercentage=0.00, resumePage=1, version=0, lastReadAt=null, completed=false; no record is created by GET. totalPages is derived from active PDF. Cache-Control: no-store.

### 10.2 Save account-private reading progress

**HTTP request**

```http
PUT /api/books/{bookId}/reading-progress
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |
| Content-Type | String | Yes | application/json |

**Request body and fields**

```json
{
  "documentId": "11111111-1111-4111-8111-111111111111",
  "currentPage": 93,
  "version": 0,
  "operationId": "22222222-2222-4222-8222-222222222222"
}
```

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| documentId | UUID | Yes | @NotNull; must equal active PDF documentId. |
| currentPage | Integer | Yes | @Min(1); service requires <= active pageCount; omission becomes 0 and fails. |
| version | Integer (int64) | Yes | Required and non-null during JSON deserialization; @Min(0); 0 on first save. |
| operationId | UUID | Yes | @NotNull; retain same UUID and exact payload for retries. |

**Example cURL**

```sh
curl -i -X PUT "${API_BASE}/api/books/1/reading-progress" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"documentId":"11111111-1111-4111-8111-111111111111","currentPage":93,"version":0,"operationId":"22222222-2222-4222-8222-222222222222"}'
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "bookId": 1,
  "documentId": "11111111-1111-4111-8111-111111111111",
  "currentPage": 93,
  "totalPages": 144,
  "pagesRead": 93,
  "progressPercentage": 64.58,
  "lastReadAt": "2026-10-01T06:00:00Z",
  "completed": false,
  "resumePage": 93,
  "version": 1
}
```

**Response fields:** See [ReadingProgress](#readingprogress).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). Page outside 1..totalPages; invalid/missing fields or revision. |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |
| 404 | Book or active PDF not found. |
| 409 | Revision conflict returns ReadingProgress; replaced PDF/reused operation returns ApiError. |

**Business / implementation notes**

See reading synchronization rules below. Exact accepted-operation retry returns latest progress with 200, not necessarily the original snapshot, without new timestamp/revision. Completion never writes Book.completed. Cache-Control: no-store.

### 10.3 Batch account-private reading summaries

**HTTP request**

```http
GET /api/books/reading-summaries
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Query parameters**

| Parameter | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| bookIds | List<Integer (int64)> | Yes | 1–100 supplied IDs; comma-separated. Duplicates deduplicated after count validation. | 1,2 |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/books/reading-summaries?bookIds=1%2C2" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
[
  {
    "bookId": 1,
    "document": {
      "bookId": 1,
      "documentId": "11111111-1111-4111-8111-111111111111",
      "fileName": "effective-java.pdf",
      "fileSize": 100000,
      "mimeType": "application/pdf",
      "pageCount": 144,
      "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "sourceType": "UPLOAD",
      "active": true,
      "createdAt": "2026-10-01T06:00:00Z"
    },
    "progress": {
      "bookId": 1,
      "documentId": "11111111-1111-4111-8111-111111111111",
      "currentPage": 93,
      "totalPages": 144,
      "pagesRead": 93,
      "progressPercentage": 64.58,
      "lastReadAt": "2026-10-01T06:00:00Z",
      "completed": false,
      "resumePage": 93,
      "version": 1
    }
  },
  {
    "bookId": 2,
    "document": null,
    "progress": null
  }
]
```

**Response fields:** See [Summary](#summary). Response is an unpaginated array.

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). Empty, over 100, malformed or missing list. |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |
| 404 | Any requested ID is missing or outside accessible library/workspace. |

**Business / implementation notes**

Results ordered by bookId ascending. Entire request fails if any unique ID is inaccessible. No PDF => document=null and progress=null. PDF without saved progress => first-open progress. Cache-Control: no-store. This is batching, not pagination.

## 11. Google Drive

### 11.1 Start Google Drive connection

**HTTP request**

```http
POST /api/integrations/google-drive/connect
```

**Authentication / authorization:** Authenticated. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/integrations/google-drive/connect" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -c drive-cookies.txt
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "authorizationUrl": "https://accounts.google.com/o/oauth2/v2/auth?<generated-oauth-parameters>"
}
```

**Response fields:** See [AuthorizationURL](#authorizationurl).

**Error responses**

| Status | Condition |
| --- | --- |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 503 | Drive integration disabled/not configured. |

**Business / implementation notes**

Requests https://www.googleapis.com/auth/drive.file with OAuth PKCE S256, offline access and consent. Sets booker_drive_binding cookie: HttpOnly, SameSite=Lax, callback path, ten-minute lifetime; Secure when request.isSecure(). State expires after ten minutes and is single use. Open authorizationUrl in same browser with cookie. Cache-Control: no-store.

### 11.2 Complete Google OAuth callback

**HTTP request**

```http
GET /api/integrations/google-drive/callback
```

**Authentication / authorization:** No. See access-label definitions in section 2.

**Query parameters**

| Parameter | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| state | String | Yes in successful flow | Binding state; service rejects null or length >200. | <state-from-consent-url> |
| code | String | Yes in successful flow | Google authorization code; service rejects null or length >4096. | <google-authorization-code> |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Cookie | String | Yes in successful flow | booker_drive_binding=<binding-cookie> from connect. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/integrations/google-drive/callback?state=%3Cstate-from-consent-url%3E&code=%3Cgoogle-authorization-code%3E" \
  -b drive-cookies.txt
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: text/html
```

```html
<!doctype html><html lang="en"><meta charset="utf-8"><title>Google Drive connected</title><p>Google Drive is connected. Close this tab and return to your bookshelf.</p></html>
```

Response Fields: None (HTML representation).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). Missing/expired/replayed/browser-mismatched state, code or required grant. |
| 403 | Google credential exchange denied. |
| 503 | Drive unavailable/not configured. |

**Business / implementation notes**

Controller query parameters are optional at binding time, but service requires state, code and cookie. Validates state/cookie instead of API authentication. Google error query is not handled separately; a denial without code yields 400. Returns HTML, clears binding cookie, no redirect or Google tokens in response. Cache-Control: no-store.

### 11.3 Check Google Drive connection

**HTTP request**

```http
GET /api/integrations/google-drive/connection
```

**Authentication / authorization:** Authenticated. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/integrations/google-drive/connection" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "connected": false
}
```

**Response fields:** See [Connection](#connection).

**Error responses**

| Status | Condition |
| --- | --- |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |

**Business / implementation notes**

connected=false when disabled or no local connection exists. Does not refresh/check live Google credentials. Cache-Control: no-store.

### 11.4 Disconnect Google Drive

**HTTP request**

```http
DELETE /api/integrations/google-drive/connection
```

**Authentication / authorization:** Authenticated. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X DELETE "${API_BASE}/api/integrations/google-drive/connection" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 204 No Content
```

Response Body: None.

Response Fields: None.

**Error responses**

| Status | Condition |
| --- | --- |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |

**Business / implementation notes**

Removes local connection and OAuth states; marks pending imports FAILED. Already disconnected succeeds. Running import may finish; imported PDFs remain. Does not revoke consent at Google; users may revoke in Google account settings.

### 11.5 Get Google Picker configuration

**HTTP request**

```http
GET /api/integrations/google-drive/picker
```

**Authentication / authorization:** Authenticated. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/integrations/google-drive/picker" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "accessToken": "<short-lived-google-access-token>",
  "apiKey": "<restricted-public-picker-key>",
  "appId": "<google-project-number>"
}
```

**Response fields:** See [Picker](#picker).

**Error responses**

| Status | Condition |
| --- | --- |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Revoked/denied Google grant; local connection removed on refresh 403. |
| 409 | Connect Drive first. |
| 503 | Picker missing configuration, Drive disabled or Google unavailable/rate-limited. |

**Business / implementation notes**

Keep response in memory only; Cache-Control: no-store. Contains short-lived Google access token, public restricted API key and project number, never refresh token or OAuth secret.

### 11.6 Queue Google Drive PDF import

**HTTP request**

```http
POST /api/books/{bookId}/document/imports/google-drive
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |
| Content-Type | String | Yes | application/json |
| Idempotency-Key | UUID | Yes | Durable import ID; reuse for same file/book/account retry. |

**Request body and fields**

```json
{
  "fileId": "selected-drive-file-id"
}
```

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| fileId | String | Yes | Nonblank; matches `[A-Za-z0-9_-]{1,200}`; Drive file ID, never a URL. |

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/books/1/document/imports/google-drive" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: 22222222-2222-4222-8222-222222222222' \
  -d '{"fileId":"selected-drive-file-id"}'
```

**Successful response**

```http
HTTP/1.1 202 Accepted
Content-Type: application/json
```

```json
{
  "importId": "22222222-2222-4222-8222-222222222222",
  "bookId": 1,
  "fileId": "selected-drive-file-id",
  "status": "PENDING",
  "documentId": null,
  "message": null
}
```

**Response fields:** See [ImportStatus](#importstatus).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |
| 404 | Private book not accessible or operation belongs to another account/book. |
| 409 | No Drive connection or same operation reused for another file. |

**Business / implementation notes**

Location points to /api/books/{bookId}/document/imports/{operationUUID}. Replays existing operation even after disconnect. New job needs local connection. File accessibility, MIME, download permission, size and PDF validation happen asynchronously; acceptance is not proof of successful import. No public-library Drive import route.

### 11.7 Get Drive import status

**HTTP request**

```http
GET /api/books/{bookId}/document/imports/{importId}
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |
| importId | UUID | Yes | Import UUID owned by current account and book. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/books/1/document/imports/22222222-2222-4222-8222-222222222222" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "importId": "22222222-2222-4222-8222-222222222222",
  "bookId": 1,
  "fileId": "selected-drive-file-id",
  "status": "PENDING",
  "documentId": null,
  "message": null
}
```

**Response fields:** See [ImportStatus](#importstatus).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |
| 404 | Book/import missing or import not owned by this account. |

**Business / implementation notes**

Poll Location until COMPLETED or FAILED; RUNNING/PENDING are nonterminal. Worker retries 503 failures up to three attempts with 30/60-second delays; recoverable running lease is 15 minutes. Asynchronous errors appear as message/status, while GET itself returns 200. Refetch active document/progress after completion because another replacement may have occurred.

## 12. Workspace notifications

### 12.1 Subscribe to workspace events

**HTTP request**

```http
GET /api/books/events
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |
| Accept | String | Recommended | text/event-stream |
| Last-Event-ID | String | No | Int64 cursor 0..latest global event ID; omit for future only. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -N "${API_BASE}/api/books/events" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Accept: text/event-stream' \
  -H 'Last-Event-ID: 0'
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: text/event-stream
```

```text
id: 0
event: ready
retry: 3000
data: {}

:heartbeat

```

Response Fields: SSE event name, id (cursor string), retry (milliseconds on ready), data (JSON); heartbeat is a comment. See event payloads in section 16.

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Malformed, negative, overflowing or future cursor. |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |
| 503 | Per-instance capacity reached or service stopping. |

**Business / implementation notes**

Only current workspace events are delivered. Omit cursor for future events; 0 replays all retained workspace events. Initial ready event supplies cursor and retry: 3000. IDs are global opaque decimal strings with gaps. Heartbeat comments about every 15 seconds; connections expire after five minutes. Persist processed IDs and deduplicate replay; reconnect with backoff. Default capacity 200 per instance; default poll 1000ms. Cache-Control: no-cache, no-store; X-Accel-Buffering: no. Angular needs a fetch-based SSE client to send Authorization; no device/FCM push API.

## 13. Public library

### 13.1 Get public PDF metadata

**HTTP request**

```http
GET /api/public-books/{bookId}/document
```

**Authentication / authorization:** Authenticated. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/public-books/1/document" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "bookId": 1,
  "documentId": "11111111-1111-4111-8111-111111111111",
  "fileName": "effective-java.pdf",
  "fileSize": 100000,
  "mimeType": "application/pdf",
  "pageCount": 144,
  "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "sourceType": "UPLOAD",
  "active": true,
  "createdAt": "2026-10-01T06:00:00Z"
}
```

**Response fields:** See [DocumentResponse](#documentresponse).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 404 | Book or active PDF not found in accessible library. |

**Business / implementation notes**

Only active version exposed. No storage path/provider/key in response. Cache-Control: no-store.

### 13.2 Read public PDF

**HTTP request**

```http
GET /api/public-books/{bookId}/document/content
```

**Authentication / authorization:** Authenticated. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Query parameters**

| Parameter | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| download | Boolean | No | Default false; true selects attachment instead of inline. | false |
| documentId | UUID | No | Must equal active version; mismatch returns 409. Does not select historical version. | 11111111-1111-4111-8111-111111111111 |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |
| Range | String | No | Byte range, e.g. bytes=0-65535, bytes=65536-, or bytes=-1024. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/public-books/1/document/content?download=false&documentId=11111111-1111-4111-8111-111111111111" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Range: bytes=0-65535'
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/pdf
```

Response Body: Binary PDF bytes (200) or requested PDF byte ranges (206); not JSON. The cURL example requests a range.

Response Fields: None (binary representation).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 404 | Book or active PDF not found. |
| 409 | Pinned document was replaced. |
| 416 | Invalid/unsatisfiable byte range; framework response, not guaranteed ApiError. |
| 503 | Document storage/content unavailable. |

**Business / implementation notes**

200 full PDF or 206 byte range with Content-Range. Streams Resource through Spring MVC. Headers: Content-Type application/pdf, Accept-Ranges bytes, ETag quoted document UUID, Content-Disposition inline/attachment with UTF-8 filename, Cache-Control no-store, X-Content-Type-Options nosniff. Every range request requires authentication. No signed URL returned. No application-specific If-Range processing is implemented; do not rely on it instead of documentId pinning.

### 13.3 Inspect public PDF headers

**HTTP request**

```http
HEAD /api/public-books/{bookId}/document/content
```

**Authentication / authorization:** Authenticated. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Query parameters**

| Parameter | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| download | Boolean | No | Default false; true selects attachment instead of inline. | false |
| documentId | UUID | No | Must equal active version; mismatch returns 409. Does not select historical version. | 11111111-1111-4111-8111-111111111111 |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -I "${API_BASE}/api/public-books/1/document/content?download=false&documentId=11111111-1111-4111-8111-111111111111" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/pdf
```

Response Body: None.

Response Fields: None.

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 404 | Book or active PDF not found. |
| 409 | Pinned document was replaced. |
| 503 | Document storage/content unavailable. |

**Business / implementation notes**

Same authorization and version checks as GET. Returns complete 64-bit Content-Length and PDF headers without opening a byte stream. Ignores Range. Response Body: None, including HEAD errors.

### 13.4 Get workspace-shared reading progress

**HTTP request**

```http
GET /api/public-books/{bookId}/reading-progress
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/public-books/1/reading-progress" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "bookId": 1,
  "documentId": "11111111-1111-4111-8111-111111111111",
  "currentPage": 93,
  "totalPages": 144,
  "pagesRead": 93,
  "progressPercentage": 64.58,
  "lastReadAt": "2026-10-01T06:00:00Z",
  "completed": false,
  "resumePage": 93,
  "version": 1
}
```

**Response fields:** See [ReadingProgress](#readingprogress).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |
| 404 | Book or active PDF not found. |

**Business / implementation notes**

First open returns currentPage=0, pagesRead=0, progressPercentage=0.00, resumePage=1, version=0, lastReadAt=null, completed=false; no record is created by GET. totalPages is derived from active PDF. Cache-Control: no-store.

### 13.5 Save workspace-shared reading progress

**HTTP request**

```http
PUT /api/public-books/{bookId}/reading-progress
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |
| Content-Type | String | Yes | application/json |

**Request body and fields**

```json
{
  "documentId": "11111111-1111-4111-8111-111111111111",
  "currentPage": 93,
  "version": 0,
  "operationId": "22222222-2222-4222-8222-222222222222"
}
```

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| documentId | UUID | Yes | @NotNull; must equal active PDF documentId. |
| currentPage | Integer | Yes | @Min(1); service requires <= active pageCount; omission becomes 0 and fails. |
| version | Integer (int64) | Yes | Required and non-null during JSON deserialization; @Min(0); 0 on first save. |
| operationId | UUID | Yes | @NotNull; retain same UUID and exact payload for retries. |

**Example cURL**

```sh
curl -i -X PUT "${API_BASE}/api/public-books/1/reading-progress" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"documentId":"11111111-1111-4111-8111-111111111111","currentPage":93,"version":0,"operationId":"22222222-2222-4222-8222-222222222222"}'
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "bookId": 1,
  "documentId": "11111111-1111-4111-8111-111111111111",
  "currentPage": 93,
  "totalPages": 144,
  "pagesRead": 93,
  "progressPercentage": 64.58,
  "lastReadAt": "2026-10-01T06:00:00Z",
  "completed": false,
  "resumePage": 93,
  "version": 1
}
```

**Response fields:** See [ReadingProgress](#readingprogress).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). Page outside 1..totalPages; invalid/missing fields or revision. |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |
| 404 | Book or active PDF not found. |
| 409 | Revision conflict returns ReadingProgress; replaced PDF/reused operation returns ApiError. |

**Business / implementation notes**

See reading synchronization rules below. Exact accepted-operation retry returns latest progress with 200, not necessarily the original snapshot, without new timestamp/revision. Completion never writes Book.completed. Cache-Control: no-store.

### 13.6 Batch workspace-shared reading summaries

**HTTP request**

```http
GET /api/public-books/reading-summaries
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Query parameters**

| Parameter | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| bookIds | List<Integer (int64)> | Yes | 1–100 supplied IDs; comma-separated. Duplicates deduplicated after count validation. | 1,2 |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/public-books/reading-summaries?bookIds=1%2C2" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
[
  {
    "bookId": 1,
    "document": {
      "bookId": 1,
      "documentId": "11111111-1111-4111-8111-111111111111",
      "fileName": "effective-java.pdf",
      "fileSize": 100000,
      "mimeType": "application/pdf",
      "pageCount": 144,
      "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "sourceType": "UPLOAD",
      "active": true,
      "createdAt": "2026-10-01T06:00:00Z"
    },
    "progress": {
      "bookId": 1,
      "documentId": "11111111-1111-4111-8111-111111111111",
      "currentPage": 93,
      "totalPages": 144,
      "pagesRead": 93,
      "progressPercentage": 64.58,
      "lastReadAt": "2026-10-01T06:00:00Z",
      "completed": false,
      "resumePage": 93,
      "version": 1
    }
  },
  {
    "bookId": 2,
    "document": null,
    "progress": null
  }
]
```

**Response fields:** See [Summary](#summary). Response is an unpaginated array.

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). Empty, over 100, malformed or missing list. |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |
| 404 | Any requested ID is missing or outside accessible library/workspace. |

**Business / implementation notes**

Results ordered by bookId ascending. Entire request fails if any unique ID is inaccessible. No PDF => document=null and progress=null. PDF without saved progress => first-open progress. Cache-Control: no-store. This is batching, not pagination.

### 13.7 List or search public books

**HTTP request**

```http
GET /api/public-books
```

**Authentication / authorization:** Authenticated. See access-label definitions in section 2.

**Query parameters**

| Parameter | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| author | String | No | Exact author match; combined with title using AND. | Joshua Bloch |
| title | String | No | Exact title match. | Effective Java |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/public-books?author=Joshua+Bloch&title=Effective+Java" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
[
  {
    "id": 1,
    "title": "Effective Java",
    "author": "Joshua Bloch",
    "publishedDate": "2018",
    "description": "Java best practices",
    "completed": false
  }
]
```

**Response fields:** See [BookResponse](#bookresponse). Response is an unpaginated array.

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |

**Business / implementation notes**

Global catalogue across workspaces, but authentication required. Unpaginated, exact case-sensitive filters; both filters use AND; empty result []. Ordering not explicitly specified. Fetch reading-summaries separately for workspace progress.

### 13.8 Get public book

**HTTP request**

```http
GET /api/public-books/{bookId}
```

**Authentication / authorization:** Authenticated. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/public-books/1" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "id": 1,
  "title": "Effective Java",
  "author": "Joshua Bloch",
  "publishedDate": "2018",
  "description": "Java best practices",
  "completed": false
}
```

**Response fields:** See [BookResponse](#bookresponse).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 404 | Missing public book or private book ID, even for its owner/Super Admin. |

**Business / implementation notes**

Identity and authorization are derived from the authenticated account; no client workspace selector is used.

### 13.9 Create public book

**HTTP request**

```http
POST /api/public-books
```

**Authentication / authorization:** Super Admin. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |
| Content-Type | String | Yes | application/json |

**Request body and fields**

```json
{
  "title": "Effective Java",
  "author": "Joshua Bloch",
  "publishedDate": "2018",
  "description": "Java best practices",
  "completed": false
}
```

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| title | String | Yes | Nonblank; maximum 255 characters. |
| author | String | Yes | Nonblank; maximum 255 characters. |
| publishedDate | String | Yes | Nonblank; maximum 20 characters. String, not validated as a date. |
| description | String or null | No | Maximum 5000 characters. |
| completed | Boolean | No | Primitive boolean; omitted value defaults to false. Manual metadata status. |

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/public-books" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Effective Java","author":"Joshua Bloch","publishedDate":"2018","description":"Java best practices","completed":false}'
```

**Successful response**

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "id": 1,
  "title": "Effective Java",
  "author": "Joshua Bloch",
  "publishedDate": "2018",
  "description": "Java best practices",
  "completed": false
}
```

**Response fields:** See [BookResponse](#bookresponse).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Super Admin role required; security-filter body is not fixed. |
| 409 | Duplicate exact author/title pair in public library. |

**Business / implementation notes**

Location points to public book detail. Public pair uniqueness is global and independent of private pairs. No book-count quota. No private book.created event; no client workspace selector.

### 13.10 Replace public book metadata

**HTTP request**

```http
PUT /api/public-books/{bookId}
```

**Authentication / authorization:** Super Admin. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |
| Content-Type | String | Yes | application/json |

**Request body and fields**

```json
{
  "title": "Effective Java",
  "author": "Joshua Bloch",
  "publishedDate": "2018",
  "description": "Java best practices",
  "completed": false
}
```

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| title | String | Yes | Nonblank; maximum 255 characters. |
| author | String | Yes | Nonblank; maximum 255 characters. |
| publishedDate | String | Yes | Nonblank; maximum 20 characters. String, not validated as a date. |
| description | String or null | No | Maximum 5000 characters. |
| completed | Boolean | No | Primitive boolean; omitted value defaults to false. Manual metadata status. |

**Example cURL**

```sh
curl -i -X PUT "${API_BASE}/api/public-books/1" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Effective Java","author":"Joshua Bloch","publishedDate":"2018","description":"Java best practices","completed":false}'
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "id": 1,
  "title": "Effective Java",
  "author": "Joshua Bloch",
  "publishedDate": "2018",
  "description": "Java best practices",
  "completed": false
}
```

**Response fields:** See [BookResponse](#bookresponse).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Super Admin role required; security-filter body is not fixed. |
| 404 | Public book not found. |
| 409 | Duplicate public author/title pair. |

**Business / implementation notes**

Full BookRequest replacement; omitted completed becomes false and optional description can clear. Cannot change identity/library scope. Does not replace PDF or alter reading progress.

### 13.11 Delete public book

**HTTP request**

```http
DELETE /api/public-books/{bookId}
```

**Authentication / authorization:** Super Admin. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X DELETE "${API_BASE}/api/public-books/1" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 204 No Content
```

Response Body: None.

Response Fields: None.

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Super Admin role required; security-filter body is not fixed. |
| 404 | Public book not found, including repeated deletion. |

**Business / implementation notes**

Hard delete. Removes document metadata, workspace progress and operation receipts transactionally; durably queues all retained document files for asynchronous cleanup. Does not delete private books.

### 13.12 Upload or replace public PDF

**HTTP request**

```http
POST /api/public-books/{bookId}/document
```

**Authentication / authorization:** Super Admin. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| bookId | Integer (int64) | Yes | Book in route-specific accessible library. |

**Query parameters**

| Parameter | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| fileName | String | Yes | Sanitized filename 1–255 characters ending .pdf. | effective-java.pdf |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |
| Content-Type | String | Yes | application/pdf |
| Idempotency-Key | UUID | Yes | One durable UUID per admin/book upload operation. |

**Request body and fields**

Raw PDF bytes from effective-java.pdf.

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| body | Binary PDF bytes | Yes | Raw application/pdf request; not multipart; nonempty validated PDF. |

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/public-books/1/document?fileName=effective-java.pdf" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/pdf' \
  -H 'Idempotency-Key: 22222222-2222-4222-8222-222222222222' \
  --data-binary @effective-java.pdf
```

**Successful response**

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "bookId": 1,
  "documentId": "11111111-1111-4111-8111-111111111111",
  "fileName": "effective-java.pdf",
  "fileSize": 100000,
  "mimeType": "application/pdf",
  "pageCount": 144,
  "checksum": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "sourceType": "UPLOAD",
  "active": true,
  "createdAt": "2026-10-01T06:00:00Z"
}
```

**Response fields:** See [DocumentResponse](#documentresponse).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). Missing/invalid filename, header or empty PDF. |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Super Admin role required; security-filter body is not fixed. |
| 404 | Public book not found. |
| 415 | Invalid extension or unsafe/invalid/encrypted PDF. |
| 503 | Storage unavailable or validator busy. |

**Business / implementation notes**

Raw application/pdf, not multipart. No private book-count, PDF byte-size, page-count or workspace storage quotas on this route; infrastructure may impose limits. Same immutable versioning/retry rules as private upload. Required fileName is query parameter. Replay may reference inactive version; refetch active metadata. Location points to public document metadata; Cache-Control: no-store.

## 14. Public library book requests

### 14.1 Request public library book

**HTTP request**

```http
POST /api/public-book-requests
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |
| Content-Type | String | Yes | application/json |

**Request body and fields**

```json
{
  "title": "Effective Java",
  "authorName": "Joshua Bloch"
}
```

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| title | String | Yes | Nonblank; maximum 255 characters; trimmed. |
| authorName | String | Yes | Nonblank; maximum 255 characters; trimmed. This field is authorName, not author. |

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/public-book-requests" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"title":"Effective Java","authorName":"Joshua Bloch"}'
```

**Successful response**

```http
HTTP/1.1 201 Created
Content-Type: application/json
```

```json
{
  "id": "44444444-4444-4444-8444-444444444444",
  "title": "Effective Java",
  "authorName": "Joshua Bloch",
  "workspaceId": "33333333-3333-4333-8333-333333333333",
  "requesterEmail": "owner@example.com",
  "status": "PENDING",
  "bookId": null,
  "reviewedBy": null,
  "createdAt": "2026-10-01T06:00:00Z",
  "reviewedAt": null
}
```

**Response fields:** See [PublicLibraryBookRequest](#publiclibrarybookrequest).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |
| 409 | Existing public book or duplicate pending title/authorName request in current workspace. |
| 429 | Workspace already created 10 requests in this UTC calendar month; Retry-After gives seconds until the next month. |

**Monthly limit response**

```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/json
Retry-After: 86400
```

```json
{
  "dateTime": "2026-10-31T00:00:00",
  "status": 429,
  "error": "Too Many Requests",
  "message": "Your workspace can submit at most 10 book requests per UTC calendar month",
  "path": "/api/public-book-requests"
}
```

`Retry-After` is an integer number of seconds, rounded up to the next UTC month;
86400 is illustrative. `dateTime` uses the existing server-local error timestamp
format and is not the reset timestamp.

**Business / implementation notes**

Workspace and requester email derived from authentication. Title/authorName trimmed before duplicate check. New status PENDING. No Location header is explicitly set. The submission transaction
enqueues an HTML/plain-text request email for every persisted SUPER_ADMIN account.
The response does not wait for RabbitMQ publication or SMTP; a 201 confirms the request was persisted, not
that notification email was delivered. Duplicate/invalid submissions create no
additional email receipts. Without a provisioned Super Admin, the request still
succeeds but no administrator email is queued.

Maximum 10 successfully created requests per workspace per UTC calendar month,
shared by all accounts and both FREE/PRO plans. All request statuses count, including
reviewed requests. Duplicate, invalid and rolled-back submissions do not consume
allowance. Existing current-month requests count on deployment; no manual counter
reset is needed. Workspace locking and READ COMMITTED counts enforce the limit
across parallel requests and replicas. A denied submission creates neither a request
nor an email receipt. No quota-usage endpoint is implemented. See the
[monthly policy](docs/public-library-requests.md#monthly-workspace-request-limit).

### 14.2 List workspace book requests

**HTTP request**

```http
GET /api/public-book-requests
```

**Authentication / authorization:** Workspace. See access-label definitions in section 2.

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/public-book-requests" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
[
  {
    "id": "44444444-4444-4444-8444-444444444444",
    "title": "Effective Java",
    "authorName": "Joshua Bloch",
    "workspaceId": "33333333-3333-4333-8333-333333333333",
    "requesterEmail": "owner@example.com",
    "status": "PENDING",
    "bookId": null,
    "reviewedBy": null,
    "createdAt": "2026-10-01T06:00:00Z",
    "reviewedAt": null
  }
]
```

**Response fields:** See [PublicLibraryBookRequest](#publiclibrarybookrequest). Response is an unpaginated array.

**Error responses**

| Status | Condition |
| --- | --- |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Workspace account required. |

**Business / implementation notes**

Current workspace history, newest createdAt first; unpaginated. Includes all statuses. No request status filter on this route.

### 14.3 List administrator book requests

**HTTP request**

```http
GET /api/admin/public-book-requests
```

**Authentication / authorization:** Super Admin. See access-label definitions in section 2.

**Query parameters**

| Parameter | Type | Required | Description | Example |
| --- | --- | --- | --- | --- |
| status | String | No | Exact PENDING, ACCEPTED or REJECTED; omission returns all. | PENDING |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/api/admin/public-book-requests?status=PENDING" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
[
  {
    "id": "44444444-4444-4444-8444-444444444444",
    "title": "Effective Java",
    "authorName": "Joshua Bloch",
    "workspaceId": "33333333-3333-4333-8333-333333333333",
    "requesterEmail": "owner@example.com",
    "status": "PENDING",
    "bookId": null,
    "reviewedBy": null,
    "createdAt": "2026-10-01T06:00:00Z",
    "reviewedAt": null
  }
]
```

**Response fields:** See [PublicLibraryBookRequest](#publiclibrarybookrequest). Response is an unpaginated array.

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). Invalid status string (case-sensitive). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Super Admin role required; security-filter body is not fixed. |

**Business / implementation notes**

Global workspace requests, newest createdAt first; unpaginated.

### 14.4 Accept pending book request with PDF

**HTTP request**

```http
POST /api/admin/public-book-requests/{requestId}/accept
```

**Authentication / authorization:** Super Admin. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| requestId | UUID | Yes | Public library book request ID. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |
| Content-Type | String | Yes | multipart/form-data |

**Request body and fields**

Multipart metadata={"publishedDate":"2018","description":"Java best practices","completed":false}; file=@effective-java.pdf.

| Field | Type | Required | Validation / description |
| --- | --- | --- | --- |
| metadata | JSON multipart part | Yes | JSON object, accepted as application/json or a plain form field; fields below. |
| metadata.publishedDate | String | Yes | Nonblank; maximum 20 characters. |
| metadata.description | String or null | No | Maximum 5000 characters. |
| metadata.completed | Boolean or null | No | Omitted/null means false. |
| file | Binary multipart part | Yes | Filename ending .pdf; part Content-Type application/pdf; servlet multipart size limits apply. |

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/admin/public-book-requests/44444444-4444-4444-8444-444444444444/accept" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -F 'metadata={"publishedDate":"2018","description":"Java best practices","completed":false};type=application/json' \
  -F 'file=@effective-java.pdf;type=application/pdf'
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "id": "44444444-4444-4444-8444-444444444444",
  "title": "Effective Java",
  "authorName": "Joshua Bloch",
  "workspaceId": "33333333-3333-4333-8333-333333333333",
  "requesterEmail": "owner@example.com",
  "status": "ACCEPTED",
  "bookId": 1,
  "reviewedBy": "super-admin@example.com",
  "createdAt": "2026-10-01T06:00:00Z",
  "reviewedAt": "2026-10-01T06:00:00Z"
}
```

**Response fields:** See [PublicLibraryBookRequest](#publiclibrarybookrequest).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Super Admin role required; security-filter body is not fixed. |
| 404 | Request not found. |
| 409 | Already reviewed or duplicate public book pair. |
| 413 | Configured servlet multipart limit exceeded. |
| 415 | File MIME/extension or PDF validation fails. |
| 503 | Storage unavailable or validator busy. |

**Business / implementation notes**

Title/author are taken from pending request; remaining metadata supplied in JSON part. Creates public book and PDF, changes status to ACCEPTED. Failure rolls back decision/book and leaves request pending. Servlet private multipart size caps apply, but public document service has no private page/storage quota. Persists reviewed SSE event and retryable email outbox transactionally. Cannot process again.

### 14.5 Reject pending book request

**HTTP request**

```http
POST /api/admin/public-book-requests/{requestId}/reject
```

**Authentication / authorization:** Super Admin. See access-label definitions in section 2.

**Path parameters**

| Parameter | Type | Required | Description |
| --- | --- | --- | --- |
| requestId | UUID | Yes | Public library book request ID. |

**Request headers**

| Header | Type | Required | Description |
| --- | --- | --- | --- |
| Authorization | String | Yes | Bearer <access-token>; Basic alternative. |

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X POST "${API_BASE}/api/admin/public-book-requests/44444444-4444-4444-8444-444444444444/reject" \
  -H "Authorization: Bearer $ACCESS_TOKEN"
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "id": "44444444-4444-4444-8444-444444444444",
  "title": "Effective Java",
  "authorName": "Joshua Bloch",
  "workspaceId": "33333333-3333-4333-8333-333333333333",
  "requesterEmail": "owner@example.com",
  "status": "REJECTED",
  "bookId": null,
  "reviewedBy": "super-admin@example.com",
  "createdAt": "2026-10-01T06:00:00Z",
  "reviewedAt": "2026-10-01T06:00:00Z"
}
```

**Response fields:** See [PublicLibraryBookRequest](#publiclibrarybookrequest).

**Error responses**

| Status | Condition |
| --- | --- |
| 400 | Invalid/missing request fields, typed IDs, required parameters, headers or multipart parts (as applicable). |
| 401 | Missing/invalid authentication; security-filter body is not fixed. |
| 403 | Super Admin role required; security-filter body is not fixed. |
| 404 | Request not found. |
| 409 | Request already reviewed. |

**Business / implementation notes**

Changes status to REJECTED with no bookId. Persists reviewed SSE notification and retryable email outbox transactionally. A second review returns 409; no rejection-reason request field.

## 15. Operations and legacy mapping

### 15.1 Check service health

**HTTP request**

```http
GET /actuator/health
```

**Authentication / authorization:** No. See access-label definitions in section 2.

**Request headers**

None required by this endpoint.

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/actuator/health" \
  -H 'Accept: application/json'
```

**Successful response**

```http
HTTP/1.1 200 OK
Content-Type: application/json
```

```json
{
  "status": "UP"
}
```

**Response fields:** See [Health](#health).

**Error responses**

| Status | Condition |
| --- | --- |
| 503 | Aggregate unhealthy/out-of-service status; health object rather than ApiError. |

**Business / implementation notes**

Only health is exposed by management configuration; show-details=never. Representative healthy response shown with Accept: application/json; Actuator may negotiate its vendor JSON media type when that header is omitted. Deployment health status may differ.

### 15.2 Legacy root mapping (denied)

**HTTP request**

```http
GET /
```

**Authentication / authorization:** Denied. See access-label definitions in section 2.

**Request headers**

None required by this endpoint.

**Request body and fields**

Request Body: None.

Request Fields: None.

**Example cURL**

```sh
curl -i -X GET "${API_BASE}/"
```

**Successful response**

None under the current security configuration.

Response Body: No successful body is exposed.

Response Fields: None.

**Error responses**

| Status | Condition |
| --- | --- |
| 401 | Anonymous request rejected by security. |
| 403 | Authenticated request denied by anyRequest().denyAll(). |

**Business / implementation notes**

Mapping remains in controller and may appear in OpenAPI, but no successful public response is available under current security. The controller greeting is unreachable through normal authenticated HTTP requests.

## 16. Client workflows and synchronization

### Account lifecycle

1. POST signup creates workspace/owner; POST login obtains access/refresh JWTs.
2. Send access JWT on protected APIs; GET workspace returns plan/usage.
3. Refresh once per expired session request group, replacing both tokens atomically. A concurrent refresh with the same old token loses with 401.
4. Logout revokes one refresh token and clear client credentials. Change/reset password invalidates all earlier account sessions; log in again.
5. Forgot-password returns a generic message; user copies the emailed token (or opens the configured reset link), then client submits token/newPassword to reset-password. No reset-token fetch API exists.

### Private upload and reading

Create book metadata before upload or Drive import. Persist one upload UUID per selected file, stream multipart from device/file input, then refetch active document and progress. Fetch content with authentication, optionally pinning documentId. Start the reader at one-based resumePage; only convert at a reader library's indexing boundary.

A replacement activates a new immutable document. Current progress then appears unstarted for that document. Retained historical files are not available via an exposed version-list/restore/download API. Separate metadata/progress calls can race with replacement; compare document IDs and refetch on mismatch or 409. The API streams PDFs from protected local storage; it does not return storage paths or signed object-store URLs.

### Reading synchronization rules (private and public)

| Situation | Server behavior |
| --- | --- |
| First open | No row created; page 0, resume 1, version 0, null lastReadAt. PUT page 0 is invalid. |
| Matching revision + new operation | Saves current page; advances version; server sets lastReadAt; maximum page never decreases. |
| Exact accepted-operation retry | 200 latest progress; no new version/timestamp. Must retain documentId/page/version/operationId exactly. |
| Same accepted operationId, different payload/book | 409 ApiError. |
| Stale version | 409 ReadingProgress; may merge a higher maximum and increment version; preserves resume page and lastReadAt. |
| Future version | 409 ReadingProgress without mutation. |
| Replaced documentId | 409 ApiError; reopen current document, not an old page on a new PDF. |
| Move backward | Current/resume page changes on matching save; maximum/pagesRead and completion preserved. |
| Reach final page | completed=true based on maximum==totalPages; never writes manual Book.completed. |

Persisted progress is **revision-controlled resume plus maximum reached page**, not client timestamp last-write-wins. Percentage is `pagesRead * 100 / totalPages`, rounded HALF_UP to two decimals. Reaching page 93 of 144 yields 64.58%; a rounded 100.00 does not alone prove completion. Accepted retry receipts are scoped to account for private progress and workspace for public progress. Conflicted requests are not recorded as accepted operations; refetch/reconcile before retrying with a new revision/operation.

Clients should debounce/periodically sync and flush on close/background; these are integration recommendations, not implemented Android behavior. Offline clients must retain cached PDFs, local resume state and exact pending updates themselves. On reconnect, process both 409 shapes and reconcile with the returned revision. Avoid silently applying an old revision to overwrite a later resume position. There is no separate offline-sync API or client timestamp field. Public reading state is shared by teammates within the same workspace.

### Google Drive flow

Check connection → authenticated connect → preserve browser-binding cookie and open consent URL → Google callback → recheck connection → Picker configuration → select accessible fileId → queue import with persisted UUID → poll status → refetch active PDF/progress.

Only `drive.file` scope is requested. PKCE/state/browser binding protect the callback. Refresh credentials are encrypted on the backend; import copies the PDF into application storage and verifies download permission/type/size/content. Reading does not depend on later Drive availability. Disconnect leaves completed PDFs intact. Native clients need a working same-browser cookie handoff; a connect call in a separate HTTP cookie jar does not by itself bind a different browser. A generic Drive URL is not a supported input.

### Public request review

Workspace POST request (maximum 10 per UTC month) → admin GET pending requests → admin accept with metadata/PDF or reject → workspace GET history / receive reviewed SSE event. Only PENDING can transition to ACCEPTED/REJECTED. Acceptance creates the public book atomically with decision; failures leave pending state. Committed submissions enqueue professional HTML/plain-text administrator request
notifications using persisted SUPER_ADMIN account emails, independent of bootstrap
environment values. Committed decisions enqueue separate requester email for retryable SMTP delivery; synchronous review success is not proof of email delivery. The bounded publisher polls PostgreSQL every second by default and confirms durable
RabbitMQ publication of opaque receipt IDs. A single active consumer with prefetch 1
sends one request/decision email at a time across replicas, using SMTP and
PASSWORD_RESET_FROM. Failures use delayed exponential retries (30-second initial
delay, five attempts by default); exhausted receipts remain parked in PostgreSQL.
Broker outage/saturation leaves receipts pending. Delivery is at least once;
HTTP success is not proof of broker publication or email delivery. See
[queue configuration and recovery](docs/request-email-queue.md).

### SSE payloads

`ready` has `{}` data and a cursor. `book.created` uses schemaVersion 2 and snapshots the current BookResponse fields:

```json
{
  "eventId": "17",
  "type": "book.created",
  "schemaVersion": 2,
  "occurredAt": "2026-10-01T06:00:00Z",
  "book": {
    "id": 1,
    "title": "Effective Java",
    "author": "Joshua Bloch",
    "publishedDate": "2018",
    "description": "Java best practices",
    "completed": false
  }
}
```

Public request decision data uses schemaVersion 1:

```json
{
  "eventId": "18",
  "type": "public-book-request.reviewed",
  "schemaVersion": 1,
  "requestId": "44444444-4444-4444-8444-444444444444",
  "status": "ACCEPTED",
  "bookId": 1,
  "message": "Your requested book Effective Java by Joshua Bloch has been accepted and added to the Global Public Library.",
  "occurredAt": "2026-10-01T06:00:00Z"
}
```

Rejection uses REJECTED, null bookId and the rejection message. IDs are opaque strings to avoid client numeric precision issues. Record the SSE id after processing, handle duplicates and reconnect with Last-Event-ID. No automatic event-retention policy or OS push registration is exposed.

## 17. Configuration and maintenance

Runtime settings, secret requirements and defaults are maintained in
[operations](docs/operations.md#configuration). File handling and Drive setup are
covered in [PDF reading](docs/book-reading.md).

### Contract limits

- Root GET is mapped and documented, but denied by security; use public health.
- OAuth cancellation without a code follows the missing-code 400 path. The callback
  consumes state/code/browser cookie and does not accept a native access token.
- Security-filter failures do not use controller advice. Progress 409 responses have
  two body shapes; HEAD is bodyless and Range errors use framework handling.
- No pagination, full-text search, private metadata update/delete, historical PDF
  management, billing/invite or arbitrary Google URL import endpoint exists.
- Public raw uploads bypass private quotas. Request acceptance uses multipart caps.
  LOCAL replicas need shared protected storage; no signed URL API exists.
- Production proxy limits, live Google/SMTP behavior and client implementation status
  require deployment/client verification beyond this backend contract.

### Source map and maintaining this reference

| Area | Primary implementation |
| --- | --- |
| Authentication/passwords | auth/AuthController.java, TokenService.java, PasswordController.java, PasswordService.java. |
| Signup/workspace/roles | saas/WorkspaceController.java, WorkspaceAccounts.java, WorkspacePrincipal.java, SuperAdminBootstrap.java. |
| Private books | controller/BookController.java, service/BookService.java, dto/BookRequest.java, BookResponse.java, repository/BookRepository.java. |
| PDF/content/storage | document/BookDocumentController.java, BookDocumentService.java, BookDocument.java, DocumentHttpResponse.java, PdfInspector.java; storage/LocalFileStorageService.java. |
| Progress | reading/ReadingProgressController.java, ReadingProgressService.java, ReadingProgress.java. |
| Drive | drive/GoogleDriveController.java, GoogleDriveConnectionService.java, GoogleDriveImportService.java, GoogleDriveGateway.java. |
| Public library/requests | library/PublicBookController.java, PublicBookService.java, PublicLibraryRequestController.java, PublicLibraryRequestService.java, PublicLibraryBookRequest.java. |
| SSE | controller/BookNotificationController.java, notification/BookEventStream.java, BookEventStore.java; Flyway event triggers. |
| Security/errors/OpenAPI | config/SecurityConfig.java, OpenApiConfig.java; exception/GlobalExceptionHandler.java; dto/ApiError.java. |
| Runtime configuration | src/main/resources/application.properties; src/main/resources/db/migration/. |

Java paths in this table are relative to `src/main/java/com/parvez/android/`. Source contracts were cross-checked against existing test cases including OpenApiCoverageTest (43 business mappings), TokenAuthenticationTest, PasswordManagementTest, WorkspaceIsolationTest, BookReadingIntegrationTest, BookDocumentHeadTest, ReadingCompletionTest, PublicLibraryIntegrationTest, PublicUploadHttpTest, PublicLibraryRequestIntegrationTest, GoogleDriveIntegrationTest/GatewayTest, and notification controller/stream/persistence tests. Tests check route coverage; they do not establish live provider or frontend behavior.

When adding/changing routes, compare this inventory to all Spring mappings, security rules, DTO validation, service behavior and generated `/v3/api-docs`. Existing database-backed OpenApiCoverageTest compares Swagger to Spring's handler registry. Use a dedicated disposable PostgreSQL database for integration tests as explained in [README.md](README.md); tests mutate data.

Further feature/deployment background: [PDF reading](docs/book-reading.md), [notifications](docs/book-notifications.md), [public library](docs/public-library.md), [book requests](docs/public-library-requests.md), [password management](docs/password-management.md). These guides document design and operational constraints; the route contracts above describe current behavior.
