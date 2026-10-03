# Password change and recovery

The backend supports workspace owners and Super Admin accounts using the existing PBKDF2 encoder. `PasswordService` manages the token lifecycle through the `PasswordResetDelivery` interface; `SmtpPasswordResetDelivery` handles SMTP and message formatting. Flyway V11 adds an account `credential_version` and one reset-token row per email. V1–V10 are unchanged.

## APIs

All requests use JSON and all successful responses use `Cache-Control: no-store`.

| Step | Method and path | Authentication | Body | Success |
|---|---|---|---|---|
| 36 | POST `/api/auth/change-password` | Bearer or Basic | `{"currentPassword":"old-password-123","newPassword":"new-password-123"}` | 204, empty |
| 37 | POST `/api/auth/forgot-password` | None | `{"email":"owner@example.com"}` | 202, `{"message":"If the account exists, a password reset email will be sent."}` |
| 38 | POST `/api/auth/reset-password` | None | `{"token":"emailed-token","newPassword":"new-password-123"}` | 204, empty |

New passwords must contain 12–64 characters and cannot be blank. Current password is required, at most 64 characters. Emails are normalized to lowercase and limited to 254 characters. Tokens are 43 URL-safe characters. Do not send an email, user ID, role or workspace ID to select the account for change/reset.

Errors use the existing ApiError schema: 400 invalid input, incorrect current password, or invalid/expired/used reset token; 401 unauthenticated/revoked credentials for password change; 503 reset email not configured. SMTP delivery failures return the same 202 as unknown accounts and invalidate the newly issued token; server logs only a generic delivery warning. Unknown accounts and requests within the cooldown also return 202 without sending email. This avoids account disclosure through response bodies/statuses; synchronous SMTP can still cause timing differences.

## Reset flow

1. Submit the email to forgot-password.
2. Email contains the configured HTTPS reset URL with `token` query parameter. The frontend/deep-link handler must collect a new password and POST token/newPassword to reset-password. This backend does not provide a reset screen.
3. Token has 256 random bits; only its SHA-256 digest is stored. Default expiry is 30 minutes. A new email after the 60-second per-account cooldown replaces the earlier token.
4. Reset consumes the token once, changes the PBKDF2 hash, increments credential version, and removes every refresh/reset token for that account in one transaction. Change-password does the same after checking the current password. Login/refresh and password changes lock the same account row to prevent concurrent issuance with stale credentials.
5. All prior access JWTs fail immediately on subsequent authentication; older JWTs with no version claim are treated as version zero until the first password change. Log in again with the new password. Normal logout behavior remains unchanged.

## Deployment

Configure these backend environment variables (also available in compose and `.env.example`):

- `SMTP_HOST`, `SMTP_PORT` (587), `SMTP_USERNAME`, `SMTP_PASSWORD`.
- `SMTP_AUTH` (true), `SMTP_STARTTLS` (true; TLS is also required when enabled).
- `PASSWORD_RESET_FROM`: sender accepted by your SMTP provider.
- `PASSWORD_RESET_URL`: HTTPS frontend/deep-link landing page, without a fragment. Existing query parameters are allowed.
- `PASSWORD_RESET_TTL`: ISO-8601 duration, default PT30M, positive and at most 24 hours.

SMTP connections/read/write have five-second timeouts. Secrets stay on the backend. Password reset is unavailable until SMTP, sender and URL are configured; signup/login/change-password continue to work. Real email delivery needs provider configuration and has not been verified with a live provider.

Apply request rate limits at the reverse proxy for login/change/forgot/reset, particularly unknown-email traffic and repeated invalid reset attempts; the built-in account cooldown limits reset email issuance, not global traffic. Do not log reset URL query strings. Serve the reset page without third-party scripts and with a restrictive Referrer-Policy, and remove the token from browser history after capturing it. Periodically purge expired reset rows where requested_at is older than the cooldown; retaining expired rows is safe and each account has at most one.

Android and Angular UI changes are not included. Swagger exposes all three operations. API inventory and Android reference include them for client implementation.

## Verification

All 99 backend tests passed with no failures, errors or skipped tests, including seven new password-management tests. Final password/authentication tests (11) and Maven package build also passed. Tests cover session revocation, owner and Super Admin password changes, wrong passwords, reset-token hashing/expiry/replay/replacement/concurrent consumption, cooldown, generic unknown-account responses, SMTP failure, and missing email configuration. Live SMTP delivery and frontend screens were not tested or implemented.
