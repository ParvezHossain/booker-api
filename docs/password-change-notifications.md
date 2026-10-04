# Password change confirmations and audit history

Every committed authenticated password change or successful recovery reset queues
a security confirmation to the **affected account's registered email address**.
Workspace owners receive their own confirmations; the same behavior applies to
Super Admin accounts without a workspace. Other workspace accounts and administrators
are not copied. Recipients and workspace IDs come from the account database, never
request selectors. Existing request JSON, 204 responses, session revocation and
recovery quotas remain unchanged.

## Audit and acceptance

`PasswordService` holds the existing account lock while replacing the password,
revoking sessions and inserting `password_change_history` plus a
`password_change_emails` receipt. The audit records a UUID, email, workspace ID
(nullable for Super Admin), database change time, `CHANGE` or `RESET`, connection IP,
bounded User-Agent and inferred browser/device. A persistence failure rolls back
the entire operation, including token consumption and recovery history. Invalid
input, wrong passwords, invalid/replayed tokens, quota rejection and rollback
create no committed confirmation or audit record. Forgot-password issuance creates
no password-change audit. Monthly recovery counts still use `password_reset_history`.

V19 retains workspace IDs as event-time snapshots without an audit-to-workspace
foreign key, so account-locked changes do not acquire an additional workspace lock.
Account deletion still cascades audit/receipt rows.

The request completing the change supplies the context, rather than the earlier
forgot-password request. `HttpServletRequest.getRemoteAddr()` supplies the address.
Under the default server configuration, `Forwarded` and `X-Forwarded-For` are ignored.
Behind a reverse proxy this records the proxy's connection address. Do not enable
forwarded-header processing without a reviewed trust boundary that strips untrusted
headers and restricts direct backend access. IP addresses can represent a proxy/VPN
and do not prove a person's identity.

User-Agent is optional, client-reported and spoofable. Control characters are removed
and at most 512 Unicode code points are stored. Simple browser/OS/device-family
inference does not identify a physical device or guarantee accuracy; missing or
unrecognized agents are shown as `Unknown`. The bounded raw agent preserves version
details for investigation. Passwords, reset tokens and authorization headers are
never included. Client metadata is escaped in HTML and never included in logs.

## Email delivery

No SMTP or RabbitMQ I/O runs on the password request thread. Missing mail configuration
or paused workers retain pending receipts and allow the password operation to succeed.
A 204 confirms database acceptance, not email delivery. Configure SMTP and a
provider-approved `PASSWORD_CHANGE_EMAIL_FROM`; blank falls back to
`PASSWORD_RESET_FROM`. Recovery encryption is independent and is not required for
confirmation delivery. `PASSWORD_RESET_TIME_ZONE` controls the displayed time;
database timestamps remain absolute instants.

Confirmations use a responsive table-based HTML layout matching the Booker recovery
email: branded header, change-details card, session-revocation guidance and a
highlighted “Wasn’t you?” notice. The bounded User-Agent appears in a separate
technical-details section with wrapping for narrow screens. A plain-text alternative
contains the same security guidance and metadata. Times use a 12-hour clock with
the configured timezone and UTC offset. The template has no external assets or scripts.

The separate `booker.password-change-emails` queue and `.dead` queue are durable,
bounded quorum queues, with persistent opaque UUID messages, mandatory routing and
publisher confirms. A dedicated scheduler publishes bounded batches. Single active
consumer, concurrency 1 and prefetch 1 preserve sequential delivery. Short transactions
claim leases and finalize delivery; SMTP runs outside database transactions/locks.
Finalization checks lease ownership, so an old worker cannot delete or change a
new worker's receipt. Listener acknowledgement follows the completion/retry commit.

On success, only the outbox receipt is deleted; audit history remains. Default
SMTP failures retry after 30, 60, 120 and 240 seconds; the fifth failure parks the
receipt. Missing sender/SMTP configuration defers delivery without consuming attempts.
Malformed messages and unexpected infrastructure failures dead-letter without an
immediate requeue loop. Pending receipts recover after confirmed-publication and
expired-lease windows even if a broker message is lost. These confirmation receipts
do not expire or become obsolete after another password replacement: each describes
a real committed event. Delivery is at least once; a crash after SMTP acceptance
before database completion or an expired lease can produce duplicate email.

Pause with `PASSWORD_CHANGE_EMAIL_ENABLED=false`; audit/outbox acceptance continues.
All knobs and defaults are listed in [operations](operations.md#configuration) and
[.env.example](../.env.example), and passed through Compose. Source/IDE runs must
export environment variables; Maven does not load `.env` automatically. This queue
and its `.dead` name must be distinct from request and recovery queue names.

## Investigation and recovery

Audit data includes account email, IP and client metadata; restrict database access.
Workspace accounts can read `/api/workspace/password-change-history`; Super Admin
can read `/api/admin/password-change-history` across all workspaces. Both use bounded
cursor pagination and no-store responses. Login history has corresponding separate
endpoints. See [account security history](account-security-history.md). Operators
can also inspect an affected account with a parameterized query (`?` represents a bound email):

```sql
SELECT id, email, workspace_id, source, changed_at, ip_address, browser, device, user_agent
FROM password_change_history WHERE email = ? ORDER BY changed_at DESC;
```

Pending/parked delivery metadata:

```sql
SELECT e.id, h.email, h.changed_at, e.attempts, e.available_at,
       e.published_at, e.failed_at, e.lease_until
FROM password_change_emails e JOIN password_change_history h ON h.id=e.id
ORDER BY e.created_at;
```

After fixing a reviewed provider/configuration failure, retry a reviewed receipt
without overriding an active lease:

```sql
UPDATE password_change_emails
SET failed_at=NULL, attempts=0, published_at=NULL, available_at=clock_timestamp()
WHERE id='replace-with-reviewed-receipt-uuid'
  AND (lease_until IS NULL OR lease_until <= clock_timestamp());
```

Monitor database backlog as well as bounded RabbitMQ queue depth. Audit and parked
receipts have no automatic retention cleanup; account deletion cascades both.
Review retention/access policy before an operator purge. Never delete current-month
`password_reset_history` to restore recovery allowance. Queue declaration changes
require a drained replacement queue or reviewed migration as described in
[request email operations](request-email-queue.md).

V17 adds these tables/indexes without rewriting existing accounts, sessions, recovery
history or encrypted recovery receipts. Earlier password changes are not reconstructed
or retroactively emailed. No undo migration exists; use a forward fix or coordinated
database restore.

## Verification

Password management tests cover metadata capture, IPv4/IPv6, ignored forwarded
headers, workspace/account isolation, failed requests, concurrent reset consumption
and atomic rollback. Migration tests upgrade populated V16 recovery/session data.
Publisher and broker tests cover confirmed opaque IDs, bounded batches, negative
confirms, missing routes, outages, SMTP retries/parking, duplicate IDs, dead letters,
lease recovery/fencing and SMTP outside transactions. SMTP is mocked in integration
tests; live provider delivery still requires deployment verification.
