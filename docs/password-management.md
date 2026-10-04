# Password change and recovery

The backend supports workspace owners and Super Admin accounts using the existing PBKDF2 encoder. `PasswordService` manages the token lifecycle and monthly reset allowance; `QueuedPasswordResetDelivery` implements durable acceptance through `PasswordResetDelivery`. A RabbitMQ consumer calls `SmtpPasswordResetDelivery` for background SMTP and message formatting. Flyway V11 adds an account `credential_version` and one reset-token row per email. V15 adds successful recovery history; V16 adds the encrypted reset-email outbox. Earlier migrations are unchanged.

## APIs

All requests use JSON and all successful responses use `Cache-Control: no-store`.

| Method and path | Authentication | Body | Success |
|---|---|---|---|
| POST `/api/auth/change-password` | Bearer or Basic | `{"currentPassword":"old-password-123","newPassword":"new-password-123"}` | 204, empty |
| POST `/api/auth/forgot-password` | None | `{"email":"owner@example.com"}` | 202, `{"message":"If the account exists, a password reset email will be sent."}` |
| POST `/api/auth/reset-password` | None | `{"token":"emailed-token","newPassword":"new-password-123"}` | 204, empty |

New passwords must contain 12–64 characters and cannot be blank. Current password is required, at most 64 characters. Emails are normalized to lowercase and limited to 254 characters. Tokens are 43 URL-safe characters. Do not send an email, user ID, role or workspace ID to select the account for change/reset.

Errors use the existing ApiError schema: 400 invalid input, incorrect current password, or invalid/expired/used reset token; 401 unauthenticated/revoked credentials for password change; 429 valid reset token but monthly allowance exhausted, with `Retry-After` seconds until the next UTC month; 503 SMTP/sender or queued-token encryption key not configured. Eligible requests return 202 after token/outbox commit; SMTP and broker failures happen in the background, retaining the original token expiry for bounded retries. Unknown accounts, requests within the cooldown and accounts at the monthly limit also return 202 without sending email. This avoids account disclosure through response bodies/statuses; database work can still cause timing differences, but HTTP no longer waits for SMTP or broker I/O.

## Password change confirmations

Both successful change-password and reset-password atomically save a request-context
audit and queue a confirmation to the affected account. Context includes the
connection IP and bounded optional User-Agent with inferred browser/device; client
metadata is untrusted and may be unknown. Failed/rejected/rolled-back requests
create neither audit nor confirmation. Missing SMTP or paused delivery retains
receipts while allowing password replacement. The existing 204 response does not
confirm delivery. See [confirmation workflow and operations](password-change-notifications.md).

Password-change records and successful login context are available through the
workspace-scoped and Super Admin [security history APIs](account-security-history.md).
These read-only APIs expose metadata without passwords, token values or encrypted mail.

## Reset flow

1. Submit the email to forgot-password. Eligible requests commit token and encrypted email receipt atomically, then return generic 202 without waiting for SMTP or RabbitMQ. The background publisher/consumer delivers the email; acceptance does not prove delivery.
2. With `PASSWORD_RESET_URL` blank, the email contains a 43-character token to copy into Swagger or an Android reset form. No web app is required. With an HTTPS reset URL configured, the email contains a link with a `token` query parameter instead. The client collects a new password and POSTs token/newPassword to reset-password. This backend does not provide a reset screen.
3. Token has 256 random bits; the authentication table stores only its SHA-256 digest and the short-lived outbox stores an AES-GCM encrypted token for delivery. Default expiry is 30 minutes from issuance; queue waits and retries never extend it. A new email after the 60-second per-account cooldown replaces the earlier token.
4. Reset validates and consumes the token, checks the monthly allowance, changes the PBKDF2 hash, increments credential version, removes every refresh/reset token and inserts successful recovery history in one transaction. A quota rejection rolls everything back. Change-password revokes sessions after checking the current password but does not consume recovery allowance. Login/refresh and password changes lock the same account row to prevent concurrent issuance with stale credentials.
5. All prior access JWTs fail immediately on subsequent authentication; older JWTs with no version claim are treated as version zero until the first password change. Log in again with the new password. Normal logout behavior remains unchanged.

## Monthly successful-reset allowance

Set `PASSWORD_RESET_MONTHLY_LIMIT=3` in the backend `.env` or deployment environment.
The default is three completed resets per account per UTC calendar month, including
Super Admin; only positive integers are accepted and invalid values fail startup.
The allowance is per registered account, not shared by the workspace. Increasing
or lowering the setting uses the same persisted history; restart all backend replicas
with the same value. Java/Maven does not load `.env` automatically: export it through
the existing startup script or IDE environment; Compose passes this setting through.

Only committed successful `POST /api/auth/reset-password` operations count. Email
requests, resends, SMTP failures, invalid/expired/replaced/replayed tokens, validation
errors, rolled-back transactions and authenticated `change-password` do not count.
After the last allowed reset, forgot-password still returns generic 202 but sends no
email. If a previously issued valid token reaches reset-password while the allowance
is exhausted (for example after lowering the setting), it returns 429 `ApiError` with
`Retry-After`; password, sessions and the token remain unchanged. Invalid or expired
tokens still return 400 even at the limit. Never automatically retry a blocked reset.
After the next month begins, request a fresh token if the old one has expired.

PostgreSQL history in `password_reset_history` remains authoritative across restarts
and replicas. The existing account row lock and READ COMMITTED reads serialize
issuance/reset checks. Database time is read after acquiring the lock, determines
the UTC month and token-expiry check, and is stored as the successful reset's
`reset_at`. A waiter crossing a month boundary uses the new month. There is no
scheduled counter reset: the query naturally excludes earlier months. Email and
browser timezone settings affect deadline display only, not the quota boundary.

V15 preserves existing accounts, credentials, refresh sessions and pending reset
tokens. Existing accounts start with zero recorded resets at upgrade; older completion
counts cannot be reconstructed from the one-row token table. Do not delete current
month history to restore allowance. There is no undo migration; recover through a
forward fix or a coordinated database restore as described in the operations guide.

## Swagger and Android without a web app

Leave `PASSWORD_RESET_URL=` blank in `.env`, configure SMTP and
`PASSWORD_RESET_FROM`, and restart the Java process with those settings exported.

1. In Swagger, execute public `POST /api/auth/forgot-password` with
   `{"email":"owner@example.com"}`. The response is the generic 202 message;
   it never contains a reset token and does not guarantee SMTP delivery.
2. Open the registered account's email and copy the token. If the email has an
   “Open token copy page” button, follow it and click “Copy token” in the browser.
   Plain-text mail still contains the line below `Reset token:`.
3. Execute public `POST /api/auth/reset-password` with
   `{"token":"<copied-43-character-token>","newPassword":"new-password-123"}`.
   Success is 204 with no body. Log in again with the new password.
4. The Android app can use the same APIs with an email form followed by token and
   new-password inputs. Trim pasted token whitespace before submitting; preserve
   the password as entered. Do not persist or log the token. Handle 400 for expired,
   invalid or used tokens, and allow a new email request after the 60-second cooldown.
   On reset 429, show the backend quota message and `Retry-After` wait; disable
   automatic retries and explain that a fresh email may be needed next month.
   A generic forgot-password 202 does not confirm remaining allowance or delivery.

The existing 256-bit token, 30-minute default expiry, single-use checks and session
revocation apply in both delivery modes. This is a long copyable token, not a
short numeric OTP. SMTP runs in a background RabbitMQ consumer, outside account
transactions. Failed sends receive bounded delayed retries until expiry/parking;
users can request a fresh email after the cooldown while allowance remains.
See [the full queue workflow and recovery guide](password-reset-email-queue.md).
Android screens are requirements for the separately maintained client, not an
implemented feature of this backend.

## Deployment

Configure these backend environment variables (also available in compose and `.env.example`):

- `SMTP_HOST`, `SMTP_PORT` (587), `SMTP_USERNAME`, `SMTP_PASSWORD`.
- `SMTP_AUTH` (true), `SMTP_STARTTLS` (true; TLS is also required when enabled).
- `PASSWORD_RESET_FROM`: sender accepted by your SMTP provider.
- `PASSWORD_RESET_URL`: optional. Leave blank for emailed token entry in Swagger/Android. If set, it must be an HTTPS frontend/app-link landing page without a fragment. Existing query parameters are allowed.
- `PASSWORD_RESET_TOKEN_PAGE_URL`: optional reachable backend `/password-reset-token` URL.
  Adds an email action that opens the copy helper. Use HTTPS in production; HTTP
  localhost/private IPv4 LAN addresses are accepted for local development.
  The URL must have no credentials, query or fragment.
- `PASSWORD_RESET_TIME_ZONE`: IANA timezone for email expiry display, default
  `Asia/Dhaka`. Invalid identifiers fail startup. Email cannot automatically detect
  each recipient’s device timezone. The browser copy helper uses the device timezone.
- `PASSWORD_RESET_TTL`: ISO-8601 duration, default PT30M, positive and at most 24 hours.
- `PASSWORD_RESET_MONTHLY_LIMIT`: positive integer, default 3 successful resets per
  account per UTC calendar month. Authenticated password changes are excluded.
- `PASSWORD_RESET_EMAIL_ENCRYPTION_KEY`: a dedicated Base64-encoded 32-byte key;
  keep stable across replicas/restarts and separate from JWT/Drive keys. Blank makes
  recovery unavailable; malformed nonblank values fail startup.
- `PASSWORD_RESET_EMAIL_ENABLED`: true; false pauses background delivery while
  accepted receipts remain pending. Queue/retry/lease settings are in the
  [queue configuration reference](password-reset-email-queue.md#configuration).

SMTP connections/read/write have five-second timeouts. Secrets stay on the backend. Password reset is unavailable until SMTP, sender and queued-token encryption key are configured; signup/login/change-password continue to work. Real email delivery needs provider configuration and has not been verified with a live provider.

Apply request rate limits at the reverse proxy for login/change/forgot/reset, particularly unknown-email traffic and repeated invalid reset attempts; the built-in account cooldown limits reset email issuance, not global traffic. Do not log reset URL query strings. Serve the reset page without third-party scripts and with a restrictive Referrer-Policy, and remove the token from browser history after capturing it. Periodically purge expired reset rows where requested_at is older than the cooldown; retaining expired rows is safe and each account has at most one.

Android and Angular UI changes are not included. Swagger exposes all three operations. API inventory and Android reference include them for client implementation.

## Email presentation and clipboard helper

Recovery emails are multipart UTF-8 HTML and plain text. The HTML includes Booker
branding, a highlighted token, its database expiry in the configured email timezone, steps and security guidance.
Email clients do not reliably run live timers, so email shows an absolute deadline
instead of a fixed “Expires in 30 minutes” message.
If configured, the copy action links to
`PASSWORD_RESET_TOKEN_PAGE_URL#token=...&expiresAt=<epoch-milliseconds>`.
The backend hosts this page; no separate Angular/web app is needed. Email clients
cannot reliably execute clipboard scripts, so copying happens after an explicit
click on the browser page, with manual selection as a fallback.

The fragment stays in the browser and is removed from history on load. The helper
validates token syntax and displays a live countdown from the expiry returned by
PostgreSQL at issuance. Its absolute deadline is formatted in the browser’s local
timezone and locale, with the timezone shown beside it. Opening it later does not
restart the lifetime. It recalculates
from the absolute deadline each second and when the tab becomes visible; at zero
it shows “Expired” and disables the copy button. The display uses the browser clock
and fragment metadata and is advisory: the reset API still enforces database expiry,
replacement and single use. Timezone conversion changes presentation only; stored
instants, fragment epoch milliseconds and countdown duration remain unchanged.
The helper never sends the token to the server or stores
it, and never queries token validity or consumes it. Old links without expiry
metadata still allow copying with an “Expiry time unavailable” message. No external assets or analytics are loaded. Responses
use no-store, no-referrer and a nonce-based restrictive CSP. On insecure LAN HTTP
or denied clipboard access, the page offers selection and manual copy. Production
must use HTTPS; HTTP local development does not protect against network tampering.
Reopen the email link if the page is reloaded after its fragment has been removed.

Keep the URL reachable from the recipient's device. For the current LAN setup:
`PASSWORD_RESET_TOKEN_PAGE_URL=http://192.168.0.122:8080/password-reset-token`.
This setting stays backend-only. Future app-link delivery via `PASSWORD_RESET_URL`
continues to use its existing query-token contract.

## Test coverage

`PasswordManagementTest` covers revocation, owner/admin password changes, incorrect
passwords, reset hashing/expiry/replay/replacement, concurrent consumption, cooldown,
generic unknown-account/quota responses, SMTP failure, missing configuration,
monthly exhaustion, account isolation, rollback and configurable limits.
`PasswordResetQuotaTest` covers startup validation, post-lock year rollover and
fractional `Retry-After` rounding; migration tests preserve populated V14 data.
`PasswordResetEmailRabbitIntegrationTest` covers background delivery, retries, stale-token suppression, leases and broker failures with disposable infrastructure and mocked SMTP. `SmtpPasswordResetDeliveryTest` covers token-only and link email formatting and URL/configuration rules. Integration tests exercise token-only email, reset, expiry, replay, cooldown and session revocation with no reset URL.
Run the suite using [the disposable database setup](operations.md#testing).
Live SMTP delivery and client reset screens require separate verification.
