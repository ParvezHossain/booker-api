# Engineering backlog and implementation specifications

This catalog tracks **proposed** work. It is not an API contract or a release
status report. Implemented behavior is documented in [README](README.md),
[API.md](API.md) and the feature guides. Every item below requires scoped design,
implementation, behavioral tests and matching documentation before being marked
implemented. Priorities describe engineering sequencing, not committed release dates.

## Implemented baseline

Workspace-isolated catalogues, JWT/Basic authentication, password management,
password-change security confirmations, successful-login context audit and paginated
workspace/Super Admin security history APIs,
immutable PDFs, local storage, authenticated ranges, revisioned reading progress,
Google Drive import, public library management, request review, shared ten-request UTC monthly submission quotas, bounded RabbitMQ request/decision email delivery and durable SSE/email
receipts exist. CI runs Maven/PostgreSQL verification and Docker build validation.
Android/Angular requirements are separate [client](ANDROID_PROMPTS.md)
[specifications](ANGULAR_PROMPTS.md); no client source is included here.

## Product features

### P2 — Private metadata update and deletion

**Objective and limitation:** Owners can create/read private books but cannot edit
metadata or remove books through an API. Manual completion cannot change after creation.

**Requirements:** Add workspace-authorized mutation contracts with explicit concurrent
edit behavior. Preserve exact author/title uniqueness and independent reading completion.
Deletion must durably queue every retained file, handle Drive jobs/progress/events and
state retry semantics. Reuse the public cleanup infrastructure where behavior matches.

**Acceptance/tests:** Cross-workspace mutation returns no information; rollback leaves
metadata/files intact; repeated deletion and concurrent replacement are deterministic.
Test quota recovery and deletion of books with retained documents/imports.
**Documentation impact:** API, reading/storage lifecycle and both client specifications.

### P2 — Complete client reading and offline workflows

**Objective and limitation:** The backend exposes upload/resume/sync contracts, but no
Android or Angular application is maintained in this repository.

**Requirements:** Implement within the actual client checkouts using their established
stack. Cover streaming upload, private cache, page restoration, durable pending operations,
explicit revision conflict choices, public workspace progress and account-switch cleanup.
Native Drive handoff needs an independently reviewed backend/browser contract; do not
mistake a Retrofit cookie for browser binding.

**Acceptance/tests:** Resume at 93/144, navigate backward without losing maximum progress,
read cached PDFs offline and sync exact retry bodies after process recreation. Exercise
large files, replacement, two devices and account isolation on real device/browser builds.
**Documentation impact:** Client specifications, client release notes and API for any new handoff.

## Architecture

### P1 — Shorten request-acceptance transactions

**Objective and limitation:** `PublicLibraryRequestService.accept` holds a request lock
and database transaction while storing/parsing a PDF. Normal direct uploads parse before
their short activation transaction. Large acceptance files can occupy connections/locks.

**Requirements:** Design staging/validation followed by transactional recheck and activation.
Preserve a single winning review, public pair uniqueness, rollback cleanup and notification/
email atomicity. Handle process crashes without activating unvalidated bytes.

**Acceptance/tests:** Concurrent accept/reject yields one decision; failed activation cleans
staging without losing the request; database locks are not held during slow parsing.
Use delayed storage/parser doubles and PostgreSQL concurrency tests.
**Documentation impact:** Request workflow and architecture transaction/cleanup guarantees.

## API

### P1 — Paginated catalogue and request history

**Objective and limitation:** Private/public catalogues and request history return unbounded
arrays. Summary batching is bounded but does not solve list growth.

**Requirements:** Add opt-in or separately versioned pagination with deterministic ordering;
preserve current clients until a documented migration. Reuse exact author/title/status
filters and tenant scopes. Bound requested sizes and document totals/cursors accurately.

**Acceptance/tests:** Stable page boundaries, empty results, invalid sizes, concurrent
inserts and workspace isolation are covered. Inspect representative query plans/indexes.
**Documentation impact:** API schemas/examples, client batching and compatibility plan.

### P2 — Account capabilities discovery

**Objective and limitation:** Login tokens and workspace API do not provide a documented
role/profile contract suitable for unified owner/admin navigation.

**Requirements:** Define an authenticated self-profile/capabilities endpoint using current
database role, with no writable role/workspace selector. Minimize returned personal data;
Super Admin has no workspace. Keep server authorization authoritative.

**Acceptance/tests:** Owner/admin capability responses match enforced permissions; revoked
credentials fail; no account enumeration or self-promotion route is introduced.
**Documentation impact:** API authentication and owner/admin client navigation specifications.

## Security

### P1 — Rate limits for costly/public endpoints

**Objective and limitation:** Reset issuance has a per-account cooldown and successful
recovery has a configurable monthly account quota (default three); SSE/parsers have
admission limits, but signup/login/reset/upload/import lack general abuse controls.
The implemented ten-request UTC monthly workspace quota limits successful book
submissions; it does not provide burst protection for rejected or unrelated traffic.

**Requirements:** Define configurable anonymous/account/workspace policies and trusted
proxy client-address rules. Bound expensive work before it starts; return documented 429
and Retry-After. Choose gateway enforcement or shared persistence according to actual
hosting; no Redis infrastructure currently exists.

**Acceptance/tests:** Limits work across intended replicas and do not allow spoofed address
bypass. Test bursts, recovery, account isolation and concurrency with safe error bodies.
**Documentation impact:** API statuses/headers and operations deployment configuration.

### P1 — Isolated PDF validation and malware scanning

**Objective and limitation:** PDFBox rejects unsafe constructs in-process, with two parser
permits and bounded object traversal. It is not a hard CPU/memory sandbox or antivirus.

**Requirements:** Stage files in quarantine; validate/scan in an isolated worker with CPU,
memory, time and temporary-disk bounds. Define fail-closed activation, retry, admin public
upload policy and cleanup. Preserve streaming and existing active-document availability.

**Acceptance/tests:** Timeouts, malformed/decompression-heavy files, scanner outages and
worker crashes cannot activate unchecked files or exhaust the web process. Use controlled
fixtures without publishing sensitive payloads.
**Documentation impact:** PDF validation, upload status contract if changed and deployment.

### P2 — Long-lived stream revocation policy

**Objective and limitation:** SSE captures workspace authorization at subscription. Password
changes invalidate new requests, but an existing stream can remain active until closure.

**Requirements:** Decide whether current five-minute lifetime is acceptable or implement
bounded periodic credential-version/account revalidation. Preserve replay and avoid
per-event expensive authentication. Specify workspace reassignment behavior explicitly.

**Acceptance/tests:** Account revocation follows the chosen maximum delay; reconnects require
current credentials; old workspace events are not leaked after a scope change.
**Documentation impact:** Authentication, SSE and operations security guarantees.

## Database

### P1 — Retention and idempotency lifecycle

**Objective and limitation:** Events, reading operation receipts, document versions and
expired session rows grow indefinitely; deletion can break replay or uncertain retries.

**Requirements:** Define independent retention windows and cleanup batches. Event pruning
needs a replay floor/resnapshot protocol; receipt pruning needs a documented retry horizon.
Inactive PDF retention must preserve referenced/current data and account for storage quota.
Add indexes/migrations only after measuring actual access patterns.

**Acceptance/tests:** Late/expired cursors and retries have deterministic outcomes, cleanup
never removes active/referenced data, and replicas cannot over-delete. Test populated upgrades,
cleanup interruption and backup restoration.
**Documentation impact:** API replay/retry semantics, schema and storage operations.

## Performance

### P2 — Shared SSE fan-out

**Objective and limitation:** Each connection polls PostgreSQL, and a global cursor row
serializes event allocation. This is appropriate for modest traffic but limits fan-out/writes.

**Requirements:** Measure database query/connection cost first. Design shared per-instance
polling or broker/CDC only if warranted. Keep transactionally durable workspace filtering,
commit order, cursor replay and backpressure without losing book/request decisions.

**Acceptance/tests:** Load results show lower database cost at a stated connection count;
rollback, reconnect, slow clients, replicas and shutdown preserve delivery guarantees.
**Documentation impact:** Notification architecture, capacity and operating limits.

## Caching

### P2 — Cache policy after measurement

**Objective and limitation:** There is no application cache. Mutable permissions/progress
and protected PDFs make indiscriminate caching unsafe; unpaginated lists should be fixed first.

**Requirements:** Identify a measured repeated-read bottleneck. Prefer bounded metadata caching
only where invalidation can be defined; include scope in keys, reauthorize protected access
and keep tokens/Picker responses/progress out of shared caches. Do not add Redis solely for
consistency with unrelated projects.

**Acceptance/tests:** Measured latency/query improvement, bounded memory, no tenant leakage,
and immediate invalidation after metadata/scope/deletion changes are demonstrated.
**Documentation impact:** Architecture/configuration and API caching semantics if changed.

## Testing

### P1 — Self-contained integration isolation

**Objective and limitation:** Integration tests require exported PostgreSQL settings and
some use fixed temp-directory names. The event test is opt-in outside CI.

**Requirements:** Provide an explicit disposable test fixture/profile or reuse an existing
container strategy. Isolate each suite's schemas/files, require the event test in full
verification, and remove test-owned resources without touching operator data.

**Acceptance/tests:** A documented single command runs all tests without skips on a supported
machine, concurrent independent suites do not collide, and failures still clean up resources.
Avoid an in-memory substitute for PostgreSQL-specific locks/triggers.
**Documentation impact:** Test setup, contributor rules and CI configuration.

## Observability

### P1 — Queue, storage and request diagnostics

**Objective and limitation:** Aggregate health and telemetry dependencies exist, but no
application queue dashboards, correlation contract or production alerts are defined.

**Requirements:** Measure pending age/retry counts for Drive, deletion and decision-mail queues;
parser rejection, SSE capacity and storage headroom. Use low-cardinality metrics and redact
account/token/file content. Define readiness separately if hosting needs it; retain secure
management endpoint exposure.

**Acceptance/tests:** Operators detect stuck queues/disk pressure using documented thresholds;
metrics do not expose secrets or tenant identifiers. Test instrumentation and alert queries
against controlled failures.
**Documentation impact:** Operations, management endpoint/API changes and dashboard ownership.

## DevOps

### P1 — Object storage and recovery exercise

**Objective and limitation:** LOCAL storage requires a shared protected volume across replicas.
Coordinated PostgreSQL/PDF restoration is documented but not automated or exercised in CI.

**Requirements:** Implement an S3-compatible provider behind `FileStorageService` with streaming,
checksums, safe keys and authorized content delivery. Plan migration of existing LOCAL metadata
and immutable bytes. Signed URLs need short-lived scoped issuance and explicit expiry semantics.
Build a restore runbook with secure encryption-key recovery and consistency checks.

**Acceptance/tests:** Range/HEAD, isolation, retry/cleanup and failed migration work with the
selected provider; restored records reference intact PDFs. Run a disposable restore exercise.
**Documentation impact:** Storage/provider configuration, API if URLs change and operations.

## CI/CD

### P2 — Supply-chain and release controls

**Objective and limitation:** CI verifies Maven/tests/Docker but no dependency/image scanning,
artifact signing, approved registry or deployment strategy exists.

**Requirements:** Maintain the pinned Action revisions and pin container images with an update process,
add dependency/image vulnerability checks with an exception policy, and produce an SBOM.
Introduce release publishing only after registry/approval/rollback requirements are defined.

**Acceptance/tests:** Builds remain reproducible, actionable scan failures have owners, fork
pull requests receive no deployment secrets, and no unapproved production deployment occurs.
**Documentation impact:** CI/release operations and dependency update ownership.

## Developer experience

### P2 — Contract tooling and consistent formatting

**Objective and limitation:** OpenAPI/Markdown route coverage exists, but manual examples/schema
validation and formatting/static analysis are not enforced comprehensively.

**Requirements:** Add focused contract/example checks and a repository-compatible Java formatting/
analysis baseline. Introduce checks incrementally without mass unrelated reformatting. Keep one
HTTP reference and link client requirements to it instead of duplicated OpenAPI snapshots.

**Acceptance/tests:** A schema/example or route mismatch fails verification with useful diagnostics;
checks run locally and in CI without exposing secrets or modifying files unexpectedly.
**Documentation impact:** Contributor commands, API maintenance and CI.

## Technical debt

### P1 — Reliable mail and import leases

**Objective and limitation:** Recovery email has encrypted short-lived outbox receipts,
bounded confirmed RabbitMQ publication, delayed retries and fenced lease completion;
SMTP runs outside database transactions. Password-change confirmations also use
short fenced leases and retain account/workspace/request-context audit after delivery. Request/decision mail uses RabbitMQ and
bounded retries, but SMTP still holds a receipt transaction. Drive leases are 15
minutes without a heartbeat/fencing token, so a slow worker may overlap a reclaim.

**Requirements:** Preserve recovery expiry, stale-token suppression, encryption and
lease ownership. Evaluate moving request/decision SMTP outside long transactions
using similarly safe leases. Add import ownership/heartbeat and conditional terminal
updates without weakening upload idempotency. Email publisher schedulers are separate;
review remaining scheduler contention and add production queue/backlog alerts.

**Acceptance/tests:** SMTP failure does not block healthy later mail, old reset delivery cannot
reactivate replaced tokens, and a reclaimed import cannot be finalized by a stale worker.
Test crash/restart, concurrent workers, duplicate delivery and account revocation.
**Documentation impact:** Passwords, requests, Drive retries, schema and operations.

### P2 — Review unused background-job infrastructure

**Objective and limitation:** JobRunr is a dependency, but application queues use scheduled
PostgreSQL workers and RabbitMQ email consumers; no application JobRunr jobs are defined.

**Requirements:** Inspect generated tables/startup behavior and deployment reliance before
removing the dependency or adopting it for actual work. Preserve queue/retry guarantees and
migration history; do not delete tables based only on absent Java imports.

**Acceptance/tests:** Startup/build and queue recovery pass without unnecessary infrastructure,
or a justified job design is documented with focused failure tests.
**Documentation impact:** Stack, architecture, dependency rationale and deployment.
