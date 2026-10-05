# Workspace email activation

New public signups create an empty FREE workspace and a pending owner. The owner
must redeem a token delivered to the registered email before authenticating.
Redemption demonstrates access to that mailbox at that time; email syntax alone
does not demonstrate ownership, and activation does not certify a person's legal
identity or permanent mailbox availability.

## Signup and activation

`POST /api/auth/signup` retains the existing request and 201 status. Its response
keeps `workspaceId`, `workspaceName`, `email`, `plan` and adds
`activationRequired: true` and `activationExpiresAt` (ISO-8601 UTC). Account,
workspace, token digest and encrypted email receipt commit together. The response
contains no token, password or session. Persistence/encryption failure rolls back
the whole signup; duplicate email returns 409 without replacing the pending
account's password or challenge.

`POST /api/auth/activate` is public and accepts `{"token":"<43-character-token>"}`.
The token selects the account; no workspace/email/role selector is accepted. Under
the existing account row lock, the service checks post-lock database time, consumes
the current unexpired token and marks the email active atomically. Success is an
empty 204 with `Cache-Control: no-store`. Log in afterward with the signup password;
activation changes neither password nor role and issues no session. Invalid,
expired, replaced and already-used tokens return the same 400 `ApiError`.
Opening an email link is not activation: only the explicit POST consumes a token,
so browser previews and mail scanners cannot silently activate an account.

The default token lifetime is one day (`EMAIL_ACTIVATION_TTL=PT24H`), configurable
from one minute to seven days using ISO-8601 duration syntax. The lifetime starts
at issuance using database time, not email delivery. Queue delays/retries never
extend it; changing the setting affects future issuance only. Expiry leaves the
workspace pending; it does not delete data or automatically activate the owner.

`POST /api/auth/resend-activation` accepts `{"email":"owner@example.com"}` and
returns generic empty 202 for pending, active and unknown accounts. Pending owners
can receive a replacement after a server-enforced 60-second cooldown. Resend
replaces the old digest and starts a new configured lifetime without recreating
the workspace or changing credentials. Concurrent resends serialize on the account
row. 202 does not prove existence, issuance or delivery. Missing activation
encryption returns 503 for all resend requests; invalid fields return 400.

## Authentication and recovery

Correct login credentials for a pending owner return 403 `ApiError` with
`message: "Email activation is required"`. Incorrect credentials and unknown email
retain generic 401. No refresh session or successful-login audit is issued for a
rejected pending login. Legacy Basic requests from pending owners return 401.
Bearer decoding/conversion and refresh also require an active account, preventing
a previously issued token from bypassing the flag.

Password recovery does not activate accounts. Pending owners receive the usual
generic forgot-password 202 without reset issuance when recovery is configured;
reset redemption requires an active account. Use resend-activation for pending
owners. Activation and recovery tokens are separate purposes/tables and cannot
be substituted. Activation consumes no password-reset monthly allowance and sends
no password-change confirmation, because no password is changed.

## Delivery and email design

`email_activation_tokens` stores only SHA-256 digests of 256-bit random tokens.
`email_activation_emails` protects the deliverable token with AES-256-GCM using
a separate stable activation key and receipt/account/hash/expiry metadata binding,
including an activation-purpose prefix. Raw tokens appear only in the delivered
email and explicit redemption request, never in database plaintext, logs, signup
responses or RabbitMQ messages.

The dedicated bounded quorum queue carries persistent opaque UUID receipt IDs.
Publication requires confirmed mandatory routing; a failed publication leaves the
database authoritative. One active consumer, concurrency/prefetch 1, calls SMTP
outside account/receipt transactions. Claim/finalization use short fenced lease
transactions. Acknowledgement follows committed completion/retry state; failures
use delayed exponential retries and then park. Lost publications and abandoned
leases can recover. Completed duplicates are harmless. Delivery remains at least
once: a crash after SMTP but before receipt completion can duplicate mail.

Expiry, replacement and activation prevent stale delivery. The publisher performs
bounded cleanup of expired/replaced/consumed ciphertext, including parked receipts,
without deleting accounts or restoring token digests. A replacement during an
in-flight SMTP call may still deliver the older email, but its token remains invalid
and the old worker cannot finalize a different lease or delete the new receipt.
Successful SMTP removes only that delivery receipt; the token remains until
activation/replacement. Activation removes the account's pending delivery receipts.

The HTML template at `templates/mail/email-activation.html` matches Booker's
green/gold security email style, with an escaped workspace/email, prominent
copyable token, precise expiry/timezone, optional action button and unsolicited
signup guidance. UTF-8 plain text carries equivalent instructions. There are no
external images or tracking resources. Never activate an unsolicited workspace.

With `EMAIL_ACTIVATION_URL` blank, the email contains a token for the existing
Android app or Swagger; no frontend is required. A configured HTTPS app-link or
frontend landing URL adds an **Open activation screen** button with
`#token=<token>&expiresAt=<epoch-ms>`. The URL must have a host and no credentials,
query or fragment. The client extracts the fragment and requires confirmation
before POST. This backend does not provide an activation GET page or web fallback;
configure the URL only when the separate client landing/app-link flow exists.

## Configuration and rollout

| Environment setting | Default / behavior |
| --- | --- |
| `EMAIL_ACTIVATION_TTL` | `PT24H`; one minute to seven days, validated at startup |
| `EMAIL_ACTIVATION_EMAIL_ENCRYPTION_KEY` | Empty; configure a separate stable base64-encoded 32-byte key before signup/resend acceptance |
| `EMAIL_ACTIVATION_FROM` | Falls back to `PASSWORD_RESET_FROM`; use a provider-approved sender |
| `EMAIL_ACTIVATION_URL` | Empty; optional HTTPS client landing/app-link URL |
| `EMAIL_ACTIVATION_TIME_ZONE` | `Asia/Dhaka`; email display only; persistence/API timestamps stay UTC |
| `EMAIL_ACTIVATION_EMAIL_ENABLED` | true; false pauses delivery but does not bypass activation |
| `EMAIL_ACTIVATION_EMAIL_QUEUE` | `booker.email-activation-emails`; queue/dead queue must differ from other mail queues |
| `EMAIL_ACTIVATION_EMAIL_POLL_MILLIS` | 1000; independent publisher scheduler |
| `EMAIL_ACTIVATION_EMAIL_BATCH_SIZE` | 20; 1–100 receipts per publication/cleanup cycle |
| `EMAIL_ACTIVATION_EMAIL_QUEUE_LIMIT` | 1000; 1–100000 broker receipts |
| `EMAIL_ACTIVATION_EMAIL_MAX_ATTEMPTS` | 5; 1–20 failures before parking |
| `EMAIL_ACTIVATION_EMAIL_RETRY_SECONDS` | 30; 1–3600; exponential delay capped at one hour |
| `EMAIL_ACTIVATION_EMAIL_REDISPATCH_SECONDS` | 300; 30–86400 for lost publication recovery |
| `EMAIL_ACTIVATION_EMAIL_LEASE_SECONDS` | 60; 30–3600 for fenced SMTP claims |

Generate a new key with `openssl rand -base64 32`, store it only in backend secret
configuration and keep it stable across replicas/restarts. Do not reuse JWT,
Drive or recovery encryption keys. Configure existing SMTP/RabbitMQ settings and
sender, deploy V20 with this backend, then deploy the client activation UI. If
SMTP is missing or delivery is paused, a configured key still permits signup to
commit encrypted pending mail; the owner remains blocked until delivery and
activation. Monitor expiry/backlog and test provider delivery before rollout.

V20 adds `email_verified` and `email_verified_at` plus the two activation tables
without editing V1–V19. Existing owners and Super Admin retain access and sessions:
their flag defaults true, timestamp null, and no retrospective email is issued.
This is compatibility grandfathering, not evidence those old mailboxes were
verified. New signup explicitly writes false; successful token redemption records
database activation time. The account role/workspace constraints remain unchanged.
There is no automatic campaign to verify old accounts or automatic pending-account
retention job. Account deletion cascades activation rows. Preserve pending tokens
and the encryption key with backups; key rotation requires a planned transition
or eligible replacement issuance, not a ciphertext/checksum repair.

See [API reference](../API.md#69-workspace-email-activation),
[Android Gemini instructions](android-email-activation-gemini.md),
[operations](operations.md) and [table map](database-table-map.md).

## Verification

`EmailActivationIntegrationTest` covers pending access denial, explicit/single-use
activation, expiry, wrong-purpose tokens, server cooldown, generic resend,
concurrent winners, atomic signup/activation rollback, duplicate signup, configurable
expiry and disabled Bearer/refresh rejection. Template/cipher tests cover escaping,
fragment links, equivalent token/expiry content and authenticated metadata binding.
`EmailActivationEmailRabbitIntegrationTest` exercises real publication, SMTP outside
transactions, replacement during delivery, delayed retries, parking/expiry cleanup,
duplicate/malformed messages, lease recovery, sequential consumers and backpressure.
The populated V19 upgrade test preserves account/session/history state and verifies
activation constraints and cascades. Existing feature fixtures represent activated
accounts; production activation remains enabled in the new flow.
