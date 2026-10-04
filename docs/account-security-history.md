# Account security history

Booker persists successful credential logins and password replacements with their
request context. Workspace accounts can read events belonging to their own workspace;
only `SUPER_ADMIN` can read every workspace's history.

## What is recorded

`POST /api/auth/login` inserts `login_history` in the same transaction as refresh-token
issuance while holding the existing account row lock. Each event records a UUID,
normalized email, workspace UUID (null for Super Admin), post-lock database time,
connection IP, bounded optional User-Agent, inferred browser and device family.
Audit persistence failure rolls token issuance back. No passwords or token values
are included. Failed/unknown-account logins, validation errors, rolled-back operations,
signup, refresh, logout and ordinary Basic/Bearer-authenticated API requests create
no login event. Internal credential logins with no HTTP context record unknown metadata.

Password `CHANGE` and recovery `RESET` events reuse `password_change_history`.
They commit with password/session changes and confirmation receipts, retain the
context of the completing request, and survive removal of delivered email receipts.
Failed, quota-rejected and rolled-back replacements do not appear. Recovery limits
still count `password_reset_history`, not the general password-change audit.

IP comes from `HttpServletRequest.getRemoteAddr()`. Under the default server
configuration forwarded headers are ignored; a reverse proxy/VPN can affect the
recorded address. User-Agent is client-reported and spoofable, stripped of control
characters and bounded to 512 Unicode code points. Browser/device are best-effort
inferences, with `Unknown` for unavailable data; they do not identify physical hardware.
See [confirmation audit details](password-change-notifications.md#audit-and-acceptance).

## APIs and authorization

| Method/path | Access / scope |
| --- | --- |
| `GET /api/workspace/login-history` | All successful logins from accounts in the authenticated workspace |
| `GET /api/workspace/password-change-history` | All committed password changes/resets from that workspace |
| `GET /api/admin/login-history` | Super Admin: all workspaces and Super Admin accounts |
| `GET /api/admin/password-change-history` | Super Admin: all password changes/resets |

Bearer and legacy Basic authentication are supported. Own-workspace endpoints derive
scope from `WorkspacePrincipal`; they accept no workspace/email/role selector. A
different workspace query parameter cannot redirect their scope. Super Admin has no
workspace and receives 403 from these endpoints. Administrator endpoints enforce
`SUPER_ADMIN` in both the security filter and service before reading history or
looking up cursors; workspace accounts receive 403. Super Admin's optional UUID
`workspaceId` filter restricts results to that captured workspace UUID. A UUID with
no matching history yields an empty page; retained history may refer to a removed workspace. With no filter, Super Admin account events have null workspace fields.

Responses use `Cache-Control: no-store`. They contain DTOs only: no password hashes,
credentials, reset/refresh tokens, storage internals or encrypted email payloads.
`workspaceId` is captured from the locked account at event time; it remains a
snapshot if the workspace is later removed. `workspaceName` is the current name,
not a historical snapshot, and is null if the captured workspace no longer exists.
Timestamps are ISO-8601 UTC instants; the email display timezone does not change
stored times, ordering or API timestamps. Full contracts are in [API.md](../API.md#72-account-security-history).

## Cursor pagination

Every endpoint accepts `limit` (default 50, range 1–100) and optional UUID `cursor`.
Responses have `items` and nullable `nextCursor`. Request the next page with that
cursor and the same endpoint/filter; null means the end. Ordering is newest time
first, then UUID descending to resolve equal timestamps. Queries fetch at most
`limit + 1` records and use indexed timestamp/UUID comparisons rather than offsets
or unbounded counts. Cursor lookup and page read share a read-only REPEATABLE READ
transaction. Empty histories return `items: []` and `nextCursor: null`.

Malformed UUIDs/limits, unknown/deleted cursors, a cursor from the other history type,
or a cursor outside the selected workspace return safe 400 `ApiError`. Unauthorized
requests return 401; role/scope rejection returns 403. Security-filter rejection
bodies are a separate contract. A cursor is a pagination marker, not authorization.

New records at the head do not repeat older records on subsequent pages. Pages are
live reads, not a frozen export; refresh from the first page to see later arrivals.
Do not invent totals, percentages or a complete historical snapshot from a page.
Display metadata as text, never interpolate the raw User-Agent into HTML.

```sh
curl -H "Authorization: Bearer $ACCESS_TOKEN" \
  "$API_BASE/api/workspace/login-history?limit=50"

curl -H "Authorization: Bearer $SUPER_ADMIN_ACCESS_TOKEN" \
  "$API_BASE/api/admin/password-change-history?workspaceId=$WORKSPACE_ID&limit=50"
```

## Upgrade and retention

V18 creates `login_history` and global/workspace/account timestamp/UUID indexes,
and adds global/workspace cursor indexes to the populated V17 password audit. Existing
accounts, tokens, password history and email receipt state are preserved.
V19 removes the login/password audit-to-workspace foreign keys while retaining
captured UUIDs. V18 remains unchanged at its original applied checksum.
Audit inserts retain the account foreign key but take no additional workspace
row lock, avoiding reverse lock ordering with workspace quota/request transactions.
Existing V17 password records become queryable; older login events cannot be reconstructed
and are not fabricated. There are no undo migrations: use a forward fix or coordinated
database restore. Hibernate continues to validate rather than migrate production.

No new environment variables or `.env` secrets are needed. Audit capture does not
depend on SMTP, RabbitMQ, email pause flags or the recovery encryption key. Environment
and disposable test instructions remain in [operations](operations.md).

Email, IP and User-Agent are sensitive activity metadata; restrict database access
and client caching. Both histories persist until operator-reviewed cleanup or
account deletion (which cascades rows); there is no automatic retention job or
HTTP deletion endpoint. Do not delete current-month `password_reset_history` to
restore recovery allowance. Monitor table growth separately from email queue depth.

## Verification

`AuthHistoryIntegrationTest` uses an isolated disposable schema to check login context,
failed-attempt exclusion, Basic/Bearer access, same-workspace accounts, cross-workspace
and Super Admin isolation, service-level authorization, filters, invalid cursors,
bounded/tied cursor pages, new arrivals, secret-free DTOs, recovery/change distinction,
receipt-independent audit, transaction rollback, concurrent login issuance and
capture while another transaction holds the workspace quota lock.
Populated V17 and V18 upgrade tests preserve audit/receipt/session state and
validate the original V18 checksum before applying V19. OpenAPI coverage
checks the four mappings, authentication, bounds and synchronized endpoint inventory.
