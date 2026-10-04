# Android password recovery implementation prompt

Copy the instructions below into Gemini 3.6 Flash in the actual Kotlin Android
project. Provide `API.md` and `docs/password-management.md` from the backend as
reference files. Do not provide the backend `.env` or credentials.

---

Implement or adjust password recovery in this Kotlin Android app to support
Booker's emailed-token flow without a web app. Complete the implementation and
applicable tests/build checks, rather than only proposing a plan.

First read the Android project's contribution instructions and inspect the
existing authentication UI, navigation, ViewModels/state, networking, DTOs,
repositories, dependency injection, error handling, session storage and tests.
Reuse the established architecture and dependencies. Explain the purpose of
changes to existing classes before editing. Preserve unrelated work. If this
checkout contains only the Spring Boot backend, report that the Android source
is missing; do not generate a replacement app or modify backend code.

## Backend contract

Password recovery works for registered workspace accounts and Super Admin.
The account is selected by registered email when requesting recovery, and by the
emailed token when resetting. No workspace/user/role selector is needed.

1. Public `POST /api/auth/forgot-password`, JSON:

   ```json
   {"email":"owner@example.com"}
   ```

   Success is HTTP 202 with:

   ```json
   {"message":"If the account exists, a password reset email will be sent."}
   ```

   Email must be nonblank, valid email syntax and at most 254 characters.
   The backend normalizes it to lowercase. Invalid input returns 400;
   unconfigured SMTP/sender/queued-token encryption key returns 503. Unknown accounts, requests within the
   cooldown and exhausted monthly reset allowance receive the same generic 202
   response. Eligible requests persist an encrypted email receipt and return before
   SMTP or RabbitMQ I/O; later broker/provider failures do not change this response. No email is issued at monthly exhaustion.
   A 202 response neither proves account existence nor guarantees email delivery.

2. With backend `PASSWORD_RESET_URL` blank, email contains `Reset token:` followed
   by a 43-character token. This is a case-sensitive token matching
   `[A-Za-z0-9_-]{43}`, not a short numeric OTP. The API never returns it.
   An optional “Open token copy page” email action opens a backend browser helper;
   users can copy there and return to the Android token-entry screen. The helper
   shows a live countdown using the database expiry carried as `expiresAt` epoch
   milliseconds in its URL fragment and formats the deadline in the browser’s local
   timezone; this display does not replace backend validation. Email uses the backend
   display timezone (default Asia/Dhaka). No native
   clipboard-link handler or new endpoint is required in the Android app.
   Default expiry is 30 minutes, configurable by the backend. Issuance is limited
   to once per account per 60 seconds; a newly issued token replaces the old one.
   Background SMTP failures have bounded delayed retries without extending token expiry. If an HTTPS reset URL is configured
   later, the email contains a link whose `token` query parameter has the same value.

3. Public `POST /api/auth/reset-password`, JSON:

   ```json
   {"token":"<copied-43-character-token>","newPassword":"new-password-123"}
   ```

   Success is HTTP 204 with an empty body. Do not try to deserialize JSON from it.
   New password must be nonblank and 12–64 characters. Invalid input or an invalid,
   expired or already-used token returns 400. Success consumes the token once and
   invalidates the account's previous access/refresh/reset tokens. Login is required.
   Successful resets are capped per account per UTC calendar month, default 3,
   configured by backend `PASSWORD_RESET_MONTHLY_LIMIT`. A valid token submitted at
   the limit returns 429 `ApiError` plus integer `Retry-After` seconds until the next
   UTC month. It leaves password, sessions and token unchanged. Invalid/expired
   tokens remain 400. Only completed resets count; requesting mail, failed attempts
   and authenticated change-password do not. The allowance is not shared by a workspace.

Errors use `ApiError` fields `dateTime`, `status`, `error`, `message`, `path`.
Use existing safe error handling with a fallback for empty/non-JSON network or
proxy responses. Preserve the API paths and JSON names exactly. Avoid duplicating
`/api/` if the existing client base URL already includes it.

## Android behavior

- Add or adjust “Forgot password?” on the login screen. Recovery must be accessible
  while logged out, with no current password, Bearer token or Basic credentials.
  Ensure public recovery calls bypass authenticated retry/refresh behavior.
- Show an email-entry form with validation, loading state and duplicate-submit
  prevention. On 202, display the generic confirmation and navigate to token entry.
  Keep messages equivalent for every email; do not claim that an email was delivered.
- Show token, new-password and confirm-password inputs. Allow explicit user paste
  and ordinary editing. Trim only surrounding whitespace from the token; preserve
  case and reject invalid characters/length. Preserve passwords exactly as entered;
  validate length, nonblank content and matching confirmation. Send only
  `token` and `newPassword`; confirmation is local validation.
- Use appropriate keyboards, password masking and visibility controls following
  existing app conventions. Provide accessible labels and inline errors.
- Provide resend and change-email actions. Resend uses forgot-password again.
  Enforce a visible 60-second resend cooldown after an accepted request and prevent
  duplicate requests. Keep cooldown behavior consistent across screen recreation.
  Explain that requesting a new token replaces the previous one. Do not implement
  automatic retries for recovery POST requests or request email repeatedly on resume.
- Do not assume when a token was issued from a local countdown; the backend decides
  expiry. On invalid/expired/used-token errors, allow correction or a new email request.
  On 503, show a recovery-unavailable message. On network failure, show a retry option
  without asserting success. If a reset response is lost, explain that the operation
  may have completed; allow login with the new password or a fresh recovery request.
- On 204, clear any existing local session through the established session manager,
  cancel pending refresh work so it cannot restore stale credentials, clear sensitive
  form state and remove recovery screens from the back stack. Show a success message
  on login and require login with the new password. Do not auto-login or reuse old tokens.
- On reset 429, display the safe backend message and Retry-After delay, keep existing
  sessions intact, and prevent automatic reset retries. Explain that a fresh email
  may be needed after allowance renewal because tokens expire independently. Do not
  hardcode three as client enforcement or infer allowance from generic email 202.
- Keep token/password values in memory only. Do not put them in SavedStateHandle,
  persistent saveable state, preferences, databases, navigation arguments, logs,
  HTTP body logging, crash reports or analytics. Clear them on completion or exit.
  Preserve suitable nonsecret screen state on rotation; after process death ask
  the user to paste the token again. Do not read email or clipboard contents automatically.

## Configuration and scope

Reuse the configured backend base URL. For a physical device, the current local
backend origin is `http://192.168.0.122:8080`; keep it configurable and allow any
needed cleartext exception only in the debug configuration. Release uses HTTPS.
SMTP/RabbitMQ credentials, queued-token encryption key, JWT secrets and all backend `.env` settings stay on the backend.
The Android app sends no email and needs no `PASSWORD_RESET_URL` setting.

Implement token entry now. Preserve any verified existing HTTPS app-link support,
but do not introduce a custom URI scheme, website, email-reading permission,
token-fetch endpoint, short OTP protocol or new networking/DI stack. Do not claim
that unimplemented app-link or web features exist.

## Verification and handoff

Use the project's existing test tools. Cover public request paths and JSON fields,
generic 202 behavior, token validation, password preservation/matching, empty 204,
400/429/503 and network errors, Retry-After handling, generic monthly-exhausted 202,
resend cooldown, duplicate submissions, sensitive-state
cleanup, session invalidation and navigation back-stack behavior. Verify that public
recovery errors do not trigger authenticated refresh loops. Run the applicable
Gradle tests, lint and debug build; report actual commands and any unavailable checks.

Finish with changed files, a concise behavior summary, test/build results and manual
steps: request email, paste its token, reset password, then log in again. Distinguish
mocked email tests from live SMTP verification. Do not send live recovery emails
unless the user requests an end-to-end test for an account they control.
