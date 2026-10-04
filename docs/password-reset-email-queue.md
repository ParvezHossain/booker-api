# Asynchronous password recovery email

`POST /api/auth/forgot-password` persists recovery state and returns the existing
generic **202** without waiting for RabbitMQ publication or SMTP. Acceptance is
not proof of email delivery. Database availability and the account row lock still
determine request latency; the API does not promise a fixed response time.

## Workflow

1. `PasswordService` checks SMTP/sender and queued-token encryption configuration,
   normalizes the email, locks the account and checks the monthly successful-reset
   allowance. Unknown accounts, the 60-second cooldown and exhausted allowance
   retain generic 202 without creating another receipt.
2. For an eligible account, the same transaction replaces the authentication token
   digest and inserts an encrypted receipt into `password_reset_emails`. Only the
   SHA-256 digest is stored in `password_reset_tokens`; the delivery token is
   AES-256-GCM encrypted with a fresh nonce and bound to receipt/account/digest/expiry.
   A persistence failure rolls back both rows. The HTTP thread performs no broker
   or SMTP I/O. Each receipt has a fresh UUID; insertion does not lock older receipts.
3. `PasswordResetEmailPublisher` polls once per second by default, cleans up a
   bounded batch of expired/replaced/consumed receipts and publishes at most 20 due
   IDs per pass. Messages contain only a 36-character UUID, with persistent delivery,
   mandatory routing and correlated publisher confirms. `published_at` is recorded
   only after confirmation. Broker outage, queue saturation or missing routing
   leaves the database receipt pending for later publication.
4. A single active consumer, concurrency/prefetch 1, processes the recovery queue.
   The worker claims a short database lease and commits it, decrypts the token,
   verifies its digest and rechecks current token/expiry immediately before SMTP.
   SMTP runs **outside database transactions and account/receipt locks**. It uses
   the existing professional HTML/plain-text email and optional reset/copy links.
5. After SMTP acceptance, the worker deletes the matching receipt/lease in a new
   transaction, then the listener acknowledges the broker message. Failure records
   a delayed retry or parked failure before acknowledgement. All state writes are
   conditional on lease ownership; an old worker cannot finalize another lease.
6. The recipient submits the token and new password to reset-password. Existing
   expiry, single-use, replacement, session revocation and configurable monthly
   successful-reset allowance remain authoritative. Queueing/delivery does not
   consume that allowance.

The token lifetime begins at **issuance**, not delivery. Retries never renew it.
Default expiry remains 30 minutes, even when the broker/provider is down. Expired,
replaced or consumed receipts are discarded; users can request a fresh email after
the cooldown while allowance remains. There is no token status/fetch API and no
client-side SMTP or RabbitMQ access.

## Configuration

All settings are backend environment variables, passed through Compose. Source/IDE
runs must export `.env`; Maven does not load it automatically.

| Variable | Default / purpose |
| --- | --- |
| `PASSWORD_RESET_EMAIL_ENCRYPTION_KEY` | Required for recovery acceptance; separate Base64-encoded 32-byte random AES key, generated with `openssl rand -base64 32`. Blank returns recovery 503; malformed nonblank values fail startup |
| `PASSWORD_RESET_EMAIL_ENABLED` | true; false pauses publisher/listener while eligible requests still persist encrypted receipts and return 202 |
| `PASSWORD_RESET_EMAIL_QUEUE` | booker.password-reset-emails; distinct from the book-request queue and both `.dead` names |
| `PASSWORD_RESET_EMAIL_POLL_MILLIS` | 1000; fixed delay between bounded publishing passes |
| `PASSWORD_RESET_EMAIL_BATCH_SIZE` | 20; range 1–100, bounds publication and stale-receipt cleanup per pass |
| `PASSWORD_RESET_EMAIL_QUEUE_LIMIT` | 1000; range 1–100000, applies to main and `.dead` quorum queues |
| `PASSWORD_RESET_EMAIL_MAX_ATTEMPTS` | 5; range 1–20 |
| `PASSWORD_RESET_EMAIL_RETRY_SECONDS` | 30; range 1–3600, exponential delay capped at one hour |
| `PASSWORD_RESET_EMAIL_REDISPATCH_SECONDS` | 300; range 30–86400, confirmed-publication recovery window |
| `PASSWORD_RESET_EMAIL_LEASE_SECONDS` | 60; range 30–3600, bounds abandoned-worker ownership; set above expected SMTP send time |
| `RABBITMQ_HEALTH_ENABLED` | true; broker health is independent of both email pause flags |

The same `RABBITMQ_*` connection/vhost/TLS settings as request email are reused.
Recovery requires `SMTP_HOST`, provider credentials/options and `PASSWORD_RESET_FROM`.
`PASSWORD_RESET_URL` remains optional: blank sends token-entry instructions; HTTPS
sends an app/frontend reset link. The optional browser copy helper and email/local
timezone behavior are unchanged. See [password management](password-management.md).

Keep the encryption key stable across restarts and identical across all replicas.
Back it up with protected deployment secrets; do not reuse JWT or Drive keys or
put it in Android/Angular configuration. Rotate only after draining or deliberately
retiring old receipts; this version does not support multiple decryption keys.
Never inspect/log email ciphertext by decrypting it into operational logs.

## Failures, duplicates and recovery

Default SMTP failure delays are 30, 60, 120 and 240 seconds; the fifth failure parks
the receipt. Missing sender/key configuration defers delivery without consuming
attempts. The token is not invalidated by a temporary SMTP failure and its original
expiry remains in force. Once parked, there is no automatic retry until operator
recovery, replacement or expiry cleanup. Decryption failures follow the same bounded
policy and never expose secrets in logs.

Delivery is **at least once**. A crash after SMTP acceptance but before completion
commit, or a worker stalled beyond its lease, can produce duplicate email. Completed
receipt duplicates are harmless. A replacement after the final validity check while
SMTP is already in flight can still deliver an old email; the old token remains
invalid and is never restored. The worker never changes authentication token state.

Malformed IDs and unexpected infrastructure failures are rejected without immediate
requeue and go to the bounded `.dead` queue. Valid pending database receipts recover
through redispatch after the publication window and expired lease. Queue loss does
not erase authoritative PostgreSQL receipts. Recovery email and book-request email
have separate single-active-consumer queues; each is sequential, and the two may
send concurrently. Broker queue limits do not bound the database backlog.

Inspect metadata only:

```sql
SELECT id, email, attempts, created_at, expires_at, published_at,
       available_at, failed_at, lease_until
FROM password_reset_emails ORDER BY created_at;
```

After fixing a reviewed provider/configuration problem, retry an unexpired current
receipt without overriding an active lease:

```sql
UPDATE password_reset_emails e
SET failed_at=NULL, attempts=0, published_at=NULL, available_at=clock_timestamp()
WHERE e.id='replace-with-reviewed-receipt-uuid'
  AND (e.lease_until IS NULL OR e.lease_until <= clock_timestamp())
  AND e.expires_at > clock_timestamp()
  AND EXISTS (SELECT 1 FROM password_reset_tokens t
              WHERE t.email=e.email AND t.token_hash=e.token_hash
                AND t.expires_at > clock_timestamp());
```

When delivery is enabled, bounded cleanup removes expired or obsolete ciphertext,
including parked failures, without changing account history. When paused, cleanup
also pauses; resume workers or purge reviewed expired receipts during maintenance.
Do not purge current-month `password_reset_history` to restore allowance.

V16 adds only the encrypted outbox and indexes; existing accounts, token digests,
sessions, reset history and request-email receipts are preserved. Existing V15
tokens have no recoverable plaintext, so no email is retroactively queued. Stop old
direct-SMTP application instances during upgrade and restart with the stable key.
There are no undo migrations; use a forward fix or coordinated database restore.
Changing declaration-time queue arguments requires a drained replacement queue or
reviewed migration, as described in [request email operations](request-email-queue.md).

## Verification

`PasswordManagementTest` checks durable acceptance, no SMTP on the request thread,
atomic rollback, generic responses, cooldown, monthly limits and reset lifecycle.
`PasswordResetEmailRabbitIntegrationTest` uses isolated PostgreSQL/RabbitMQ with
mocked SMTP to cover opaque persistent IDs, slow SMTP without request lock contention,
stale receipts, original-expiry retries, parking, saturation, duplicates, dead letters
and abandoned-lease recovery. Cipher tests cover metadata binding and key validation;
publisher tests cover bounded batches, negative confirms, missing routes and outages.
Populated-upgrade tests preserve V15 data. Live SMTP and deployment failover/throughput
require separate verification. Follow [disposable testing](operations.md#testing).
