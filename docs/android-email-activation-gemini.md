# Gemini 3.6 Flash: adapt the existing Android app to email activation

Paste the following prompt into Gemini while working in the **actual Android
repository**. Attach its repository instructions, [API.md](../API.md),
[activation contract](email-activation.md) and [Android specifications](../ANDROID_PROMPTS.md).
This backend repository does not contain the Android app; this document is an
implementation handoff, not a claim that the client has been changed.

## Copyable implementation prompt

You are updating our existing Booker Android app. Inspect and extend its current
architecture. Do not generate a replacement app, change libraries without need,
or assume file/package names. Preserve catalogue, PDF reading, workspace isolation,
SSE, JWT single-use refresh, password recovery, Drive and security history behavior.
Read the project's AGENTS.md and the supplied backend API/activation documentation
before editing. Implement the complete email activation flow and run the relevant
client tests/build. Keep changes focused and report exact files changed and checks.

### Inspect before implementation

Locate the real signup/login screens, navigation graph, public Retrofit/API client,
repository, DTO serializer, ViewModels/state, session coordinator, error mapper,
manifest/app links, string resources and existing tests. Identify any automatic
signup-to-login behavior and any stored Basic credentials. Reuse these components
and the existing design system. Follow actual SDK/dependency versions; do not add
a second networking, DI or persistence stack.

The backend is implemented with the following contracts; do not invent endpoints:

| Action | Request | Success | Relevant failure |
| --- | --- | --- | --- |
| Signup | Public POST `/api/auth/signup`, `{"workspaceName":"My Library","email":"owner@example.com","password":"replace-this-password"}` | 201 response below; pending account, no session | 400 invalid fields, 409 email registered, 503 activation unavailable |
| Activate | Public POST `/api/auth/activate`, `{"token":"<43-character-token>"}` | Empty 204; activation completed, no session | 400 invalid/expired/replaced/used token |
| Resend | Public POST `/api/auth/resend-activation`, `{"email":"owner@example.com"}` | Empty generic 202 | 400 invalid email, 503 activation unavailable |
| Login | Public POST `/api/auth/login`, unchanged email/password body | 200 existing token pair for active accounts | 401 wrong/unknown credentials; 403 with ApiError message `Email activation is required` for correct pending credentials |

Signup response:

```json
{
  "workspaceId": "33333333-3333-4333-8333-333333333333",
  "workspaceName": "My Library",
  "email": "owner@example.com",
  "plan": "FREE",
  "activationRequired": true,
  "activationExpiresAt": "2026-10-05T12:00:00Z"
}
```

Fields `workspaceId`, `workspaceName`, `email`, `plan` remain unchanged. Add
`activationRequired` and the ISO-8601 UTC `activationExpiresAt` using the current
serializer/time utilities. Normalize `/api` exactly once; follow the app's existing
base URL convention. Requests are JSON. Activation/resend have no Bearer/Basic
authentication and must not trigger token refresh or an authenticator retry.
Handle empty 204/202 without decoding a JSON success body. Application errors use
the documented ApiError; security-filter errors may have a separate body.

### Signup and pending state

Replace automatic login after signup 201 with navigation to a pending activation
screen when `activationRequired` is true. Never treat signup as authentication or
enter workspace/catalogue/SSE/download flows before login succeeds. Show the
normalized returned email and explain that the user must check their inbox/spam
and paste the emailed activation token. The default token lifetime is one day,
but the backend can configure it: display the server expiry in device local time
instead of hardcoding 24 hours. A local countdown is informative, never authoritative.

Keep pending registration distinct from an authenticated session. If remembering
pending progress across process death is needed, retain only existing safe metadata
(email, workspace identity/name and expiry) using the app's established storage.
Do not store the signup password, token or Basic credentials. Clear password state
when leaving signup. Activation can also be opened from login for a user who has
lost the pending screen or registered on a different device.

On a signup timeout, do not assume the account was not created or loop POST signup.
Offer sign-in/activation recovery. A duplicate 409 does not reveal whether the
account is active; offer sign-in or request activation without overwriting the
existing account or asking the backend to select a workspace.

### Activation screen and redemption

Use the app's current UI style: clear title, email context, token paste field,
expiry information, Confirm activation button, Resend email action, and Back to
sign in. Prefer proper labels/accessibility and localized strings. Trim accidental
outer whitespace only; tokens are case-sensitive 43-character URL-safe strings
matching `[A-Za-z0-9_-]{43}`. Validation helps entry but the server decides validity.

POST only after the user explicitly confirms. Disable duplicate submission while
loading; do not activate automatically from navigation, link opening or lifecycle
recomposition. Success 204 clears the token and pending state, explains that the
email is active and returns to login with only the email prefilled. Activation
does not issue tokens, change the password or justify a local authenticated flag.
The user signs in with the password chosen during signup.

For 400, show “This activation token is invalid, expired or already used. Try
signing in or request a new activation email.” The backend intentionally does not
distinguish these cases. For an uncertain network response, the server may already
have consumed the token; offer sign-in/resend rather than endless automatic POST
retries. Preserve harmless form state on transient failure; never dump token
contents into errors, logs or telemetry.

### Resend and login error routing

Resend submits only the email. Show a generic confirmation such as “If this account
still needs activation, an email will be sent.” A 202 does not prove the account
exists, a new token was issued or SMTP delivered it. The server enforces 60 seconds
between issuances; use a local 60-second button cooldown to reduce accidental taps,
but do not rely on it for security. A replacement invalidates older tokens and
starts a new server-configured lifetime. Resend returns no new expiry; do not claim
the old displayed deadline belongs to the replacement. Explain that the new email
contains the current deadline. Expiry does not delete/recreate the workspace.

Handle the activation-required **403 from POST login** as a typed pending-email
outcome and navigate to activation with the entered email. Do not convert it into
401, launch refresh, fall back to Basic, or route every unrelated 403 to activation.
Incorrect credentials remain 401. Pending Basic requests also receive 401. Password
recovery does not activate a pending owner; offer resend activation for this state.

Existing pre-upgrade accounts are grandfathered and still log in normally. Keep
compatibility with an older backend only if required by the project's rollout
policy: tolerate unknown response fields and make any missing activation-field
behavior explicit/tested. Never bypass a new backend's activation-required result.

### Optional email app links

Manual token entry must work without any web frontend or deep link setup. Implement
links only for the project's real owned HTTPS domain/path, coordinated with the
backend's `EMAIL_ACTIVATION_URL`. The email button targets:

```text
https://YOUR_OWNED_DOMAIN/activate#token=TOKEN&expiresAt=EPOCH_MILLISECONDS
```

There is no backend activation GET page. Use the existing Android App Links
approach, manifest `android:autoVerify` intent filter and correctly hosted
`/.well-known/assetlinks.json` with the real package/signing certificates. Do not
invent a domain, hardcode example signing fingerprints or claim verification
without checking the real configuration. A web fallback is separate client work.

Allow only the exact trusted scheme/host/path. Read the token/expiry from the URI
fragment, validate their types and prefill the same activation screen; do not POST
automatically, fetch an arbitrary URI, echo the URI to analytics or grant a session.
Handle cold/warm starts and already-open screens with the existing navigation/state
pattern. Expiry hints from links remain untrusted; the API is authoritative.
Ignore unrelated/malformed links safely and retain the manual-entry fallback.

### Security and existing behavior

Backend SMTP/RabbitMQ URLs, encryption keys, JWT secrets and sender settings never
belong in Android resources, BuildConfig, repositories or API calls. Redact bodies
and URIs containing activation/reset/refresh tokens from HTTP logging, crash
reports and analytics. Keep the activation token transient and clear it after
completion/cancellation. Share no authentication with arbitrary hosts. Preserve
the existing single-flight refresh/session-generation rules for authenticated
features; activation is an independent public flow.

### Required acceptance tests and delivery

Test observable behavior with the existing test tools and HTTP fakes:

1. Signup 201 with activationRequired=true opens pending UI without login, session
   persistence, workspace fetch, SSE or downloads; JSON/UTC expiry parsing is correct.
2. Correct pending login's 403 routes to activation; wrong password remains 401;
   unrelated authorization failures retain their existing behavior.
3. Manual token entry sends the exact public POST JSON once; 204 clears token/state
   and returns to login without attempting to decode a body or issuing local tokens.
4. Invalid, expired, replaced, replayed tokens and ambiguous network completion
   offer safe sign-in/resend recovery; 503/network failures retain useful UI state.
5. Resend uses exact email JSON, generic 202 handling, cooldown and no fabricated
   replacement expiry. Switching email/account cannot use stale responses.
6. Process death/back navigation/recomposition do not store passwords, automatically
   redeem tokens, duplicate signup or unlock authenticated screens.
7. If configured, trusted cold/warm app links only prefill; malformed/untrusted
   origins and fragments are rejected. Manual entry always remains available.
8. Activation requires a fresh successful login; ordinary activated/legacy login,
   refresh/logout, password reset, catalogue/PDF/SSE/Drive/history tests still pass.

Run the actual project's targeted tests and Gradle build/lint checks; report unavailable
emulator/device/domain infrastructure honestly. Update its README and user-flow/API
documentation. Deliver a concise file map, changed behavior, executed commands,
remaining app-link deployment settings and validation results. Do not claim Android
implementation complete until the code and client build have been verified.
