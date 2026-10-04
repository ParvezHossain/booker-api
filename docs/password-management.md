# Password change and recovery

The backend supports workspace owners and Super Admin accounts using the existing PBKDF2 encoder. `PasswordService` manages the token lifecycle through the `PasswordResetDelivery` interface; `SmtpPasswordResetDelivery` handles SMTP and message formatting. Flyway V11 adds an account `credential_version` and one reset-token row per email. V1–V10 are unchanged.

## APIs

All requests use JSON and all successful responses use `Cache-Control: no-store`.

| Method and path | Authentication | Body | Success |
|---|---|---|---|
| POST `/api/auth/change-password` | Bearer or Basic | `{"currentPassword":"old-password-123","newPassword":"new-password-123"}` | 204, empty |
| POST `/api/auth/forgot-password` | None | `{"email":"owner@example.com"}` | 202, `{"message":"If the account exists, a password reset email will be sent."}` |
| POST `/api/auth/reset-password` | None | `{"token":"emailed-token","newPassword":"new-password-123"}` | 204, empty |

New passwords must contain 12–64 characters and cannot be blank. Current password is required, at most 64 characters. Emails are normalized to lowercase and limited to 254 characters. Tokens are 43 URL-safe characters. Do not send an email, user ID, role or workspace ID to select the account for change/reset.

Errors use the existing ApiError schema: 400 invalid input, incorrect current password, or invalid/expired/used reset token; 401 unauthenticated/revoked credentials for password change; 503 reset email not configured. SMTP delivery failures return the same 202 as unknown accounts and invalidate the newly issued token; server logs only a generic delivery warning. Unknown accounts and requests within the cooldown also return 202 without sending email. This avoids account disclosure through response bodies/statuses; synchronous SMTP can still cause timing differences.

## Reset flow

1. Submit the email to forgot-password.
2. With `PASSWORD_RESET_URL` blank, the email contains a 43-character token to copy into Swagger or an Android reset form. No web app is required. With an HTTPS reset URL configured, the email contains a link with a `token` query parameter instead. The client collects a new password and POSTs token/newPassword to reset-password. This backend does not provide a reset screen.
3. Token has 256 random bits; only its SHA-256 digest is stored. Default expiry is 30 minutes. A new email after the 60-second per-account cooldown replaces the earlier token.
4. Reset consumes the token once, changes the PBKDF2 hash, increments credential version, and removes every refresh/reset token for that account in one transaction. Change-password does the same after checking the current password. Login/refresh and password changes lock the same account row to prevent concurrent issuance with stale credentials.
5. All prior access JWTs fail immediately on subsequent authentication; older JWTs with no version claim are treated as version zero until the first password change. Log in again with the new password. Normal logout behavior remains unchanged.

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

The existing 256-bit token, 30-minute default expiry, single-use checks and session
revocation apply in both delivery modes. This is a long copyable token, not a
short numeric OTP. SMTP is synchronous with no automatic reset-email retry; users
can request another email after the cooldown if delivery fails.
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
- `PASSWORD_RESET_TTL`: ISO-8601 duration, default PT30M, positive and at most 24 hours.

SMTP connections/read/write have five-second timeouts. Secrets stay on the backend. Password reset is unavailable until SMTP and sender are configured; signup/login/change-password continue to work. Real email delivery needs provider configuration and has not been verified with a live provider.

Apply request rate limits at the reverse proxy for login/change/forgot/reset, particularly unknown-email traffic and repeated invalid reset attempts; the built-in account cooldown limits reset email issuance, not global traffic. Do not log reset URL query strings. Serve the reset page without third-party scripts and with a restrictive Referrer-Policy, and remove the token from browser history after capturing it. Periodically purge expired reset rows where requested_at is older than the cooldown; retaining expired rows is safe and each account has at most one.

Android and Angular UI changes are not included. Swagger exposes all three operations. API inventory and Android reference include them for client implementation.

## Email presentation and clipboard helper

Recovery emails are multipart UTF-8 HTML and plain text. The HTML includes Booker
branding, a highlighted token, configured expiry, steps and security guidance.
If configured, the copy action links to `PASSWORD_RESET_TOKEN_PAGE_URL#token=...`.
The backend hosts this page; no separate Angular/web app is needed. Email clients
cannot reliably execute clipboard scripts, so copying happens after an explicit
click on the browser page, with manual selection as a fallback.

The fragment stays in the browser and is removed from history on load. The helper
validates only token syntax, never sends it to the server or stores it, and never
checks expiry or consumes it. No external assets or analytics are loaded. Responses
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
generic unknown-account responses, SMTP failure and missing configuration.
`SmtpPasswordResetDeliveryTest` covers token-only and link email formatting and URL/configuration rules. Integration tests exercise token-only email, reset, expiry, replay, cooldown and session revocation with no reset URL.
Run the suite using [the disposable database setup](operations.md#testing).
Live SMTP delivery and client reset screens require separate verification.
