# Database tables and migration dependencies

Booker uses one PostgreSQL database with 25 Flyway-created application tables.
Each table is created once in V1–V20. Later versions alter existing tables,
indexes or trigger functions to preserve records while introducing features.
Repeated migrations against the same database do not create duplicate tables:
Flyway records each applied version in `flyway_schema_history` and subsequent
starts validate applied files and execute pending versions only.

This guide organizes the final schema by table. The executable migration files
remain the chronological upgrade history in `src/main/resources/db/migration`.
Hibernate validates the schema; JPA manages book queries and JDBC manages the
remaining application state. Flyway's history and JobRunr-managed runtime tables
are separate from the 25 application tables listed here.

## Table inventory

| Table | Created / later changes | Identity and responsibility |
| --- | --- | --- |
| `workspaces` | V4 | UUID primary key; name, FREE/PRO entitlement and private-book quota. Its row lock serializes private quota and monthly request checks. |
| `workspace_users` | V4 / V10, V11, V20 | Normalized email primary key; password hash, workspace, role and credential version. OWNER requires a workspace; SUPER_ADMIN requires null workspace. The account row lock serializes credentials, token issuance and email activation. V20 adds active status/time; new signup is pending, older accounts are grandfathered. |
| `books` | V1 / V4, V9, V10; seeded by V2 | Shared BIGINT identity for PRIVATE and PUBLIC metadata; private rows require a workspace, public rows require null workspace. Exact author/title uniqueness applies within each private workspace and globally for public books. |
| `book_event_cursor` | V3 | Singleton transactional counter; event IDs follow commit order across book and request events. |
| `book_events` | V3 / V4, V9, V12 | BIGINT event primary key; workspace, event type and historical JSON payload. SSE replay reads this durable log. |
| `refresh_tokens` | V5 | Token-digest primary key; account and expiry. Single-use refresh consumes one row; password replacement removes account sessions. |
| `book_documents` | V6 / V8 indexes | UUID versions with stable book FK, account creator, retry operation, PDF metadata and opaque storage key. A partial unique index allows one active document per book. Bytes remain outside PostgreSQL. |
| `reading_progress` | V6 / V8 indexes | Account/book primary key; account-private position, maximum page and revision tied to a document/book pair. |
| `reading_progress_operations` | V6 / V8 indexes | Account/operation primary key; durable exact-body retry receipts referencing the original document/book pair. |
| `google_drive_connections` | V7 | Account primary key; encrypted Google refresh credential. |
| `google_drive_oauth_states` | V7 | State-digest primary key; account, browser binding, encrypted PKCE verifier and expiry. Separate short-lived authorization workflow. |
| `google_drive_imports` | V7 | UUID operation primary key; account/book/file, pending/running/completed/failed status, document result and delayed worker retry state. |
| `public_reading_progress` | V10 | Workspace/book primary key; workspace-shared public PDF position and revision. A trigger validates public-library membership and page bounds. |
| `public_reading_progress_operations` | V10 | Workspace/operation primary key; exact-body public progress retry receipts. |
| `document_file_deletions` | V10 | Opaque storage-key primary key; durable cleanup receipts survive metadata deletion and remain until file deletion succeeds. |
| `password_reset_tokens` | V11 | One row per account; unique token digest, original expiry and issuance time. No plaintext secret. |
| `public_library_book_requests` | V12 | UUID primary key; workspace/requester, exact requested title/author, review status, reviewer and optional accepted book FK. All successful creations count toward the monthly workspace request quota. |
| `public_request_emails` | V12 / V13, V14 | Request/type/recipient primary key plus unique opaque receipt UUID; durable submission/decision bodies, delayed retry, publication and parked-failure state. Completion deletes the receipt. |
| `password_reset_history` | V15 | BIGINT identity; successful recovery account/time ledger used for the UTC monthly allowance. Authenticated password changes do not enter this table. |
| `password_reset_emails` | V16 | Opaque UUID primary key; encrypted short-lived recovery token bound to account/receipt metadata, expiry and fenced lease/retry state. |
| `password_change_history` | V17 / V18 indexes, V19 workspace snapshot | UUID primary key; committed CHANGE/RESET account, captured workspace UUID, time and IP/browser/device/User-Agent. Email completion retains this audit. |
| `password_change_emails` | V17 | UUID primary/FK to password-change audit; independent confirmation publication, delayed retry and fenced lease state. |
| `login_history` | V18 / V19 workspace snapshot | UUID primary key; successful credential login account, captured workspace UUID, time and IP/browser/device/User-Agent. It commits with token issuance. |
| `email_activation_tokens` | V20 | One current digest per pending account, unique token hash, expiry and server resend cooldown. |
| `email_activation_emails` | V20 | Separate encrypted short-lived activation outbox, opaque UUID publication, delayed retries and fenced delivery leases. |

V19 removes workspace FKs from both activity histories to avoid acquiring a
workspace lock after the account lock. Their captured workspace UUIDs remain
snapshots; account FKs and deletion cascades remain. A removed workspace can have
a retained audit UUID and null current name. See [security history](account-security-history.md).

## Why existing shared tables stay shared

| Candidate | Dependency and decision |
| --- | --- |
| Split `books` into private/public tables | `Book`, `BookRepository`, `BookAccess`, document metadata, both progress families, Drive imports and accepted requests depend on the same book identity. Services lock the existing book row. Preserve the shared table and existing scope checks; splitting it requires a coordinated persistence refactor and populated-data compatibility plan. |
| Split `book_documents` by library type | Both book scopes use the same document lifecycle, storage abstraction, version/operation identity and composite document/book references. Authorization derives from the referenced book. Preserve this shared document table. |
| Split submission/decision `public_request_emails` | Both email types use the same publisher, bounded queue, receipt UUID lookup, retry state and consumer. V13 makes type/recipient uniqueness explicit; V14 preserves stable delivery identities. Preserve this typed outbox. |
| Merge `password_reset_history` with password-change audit | The reset ledger predates V17 and enforces a quota. The later audit has request context and both CHANGE/RESET sources; earlier reset rows have no corresponding audit event. Preserve both distinct responsibilities and complete current-month reset history. |
| Combine the four email outboxes | Request mail, encrypted expiring recovery/activation mail and password-change confirmation have different payloads, leases and cleanup rules. They already use separate tables and queues; preserve that separation. |
| Combine private/public progress or operations | Account-private and workspace-shared identities and authorization differ. Their tables are already distinct and use a closed internal service scope enum. Preserve each pair. |
| Separate features into PostgreSQL databases | Account/password/session/audit changes, request acceptance and document operations rely on atomic cross-table transactions and FKs. Compose's single database supports these boundaries; no additional database is needed. |

No additional table split was identified that can be performed by changing
migration files alone while preserving the existing code and behavior.

## Migration-by-migration review

| Version | Purpose and upgrade behavior |
| --- | --- |
| V1 | Creates the original book identity/metadata table. |
| V2 | Inserts the historical seed catalogue once; it is not repeated on restart. |
| V3 | Creates commit-ordered event allocation, durable snapshots and the book insert trigger. |
| V4 | Adds tenancy; existing books/events are assigned to a reserved legacy workspace, without exposing them to new signups. |
| V5 | Adds account-owned hashed refresh sessions. |
| V6 | Adds immutable document versions and distinct account progress/retry tables. |
| V7 | Separates durable Drive credentials, expiring OAuth state and import work. |
| V8 | Adds FK lookup indexes to existing document/progress tables; creates no table. |
| V9 | Adds exact author/title identity, removes ISBN and upgrades historical event payloads without changing IDs. Duplicate exact workspace pairs block the upgrade and must be resolved intentionally. |
| V10 | Adds public scope and Super Admin checks; creates workspace public progress/retry and independent file cleanup tables. Replaces the book trigger to exclude public catalogue inserts. |
| V11 | Adds account credential versions and one recovery-token row per account. |
| V12 | Adds request audit, decision receipts and event type. |
| V13 | Evolves existing receipts to support separate submission recipients and decision mail; queued content survives. |
| V14 | Adds UUID publication identity and bounded-queue retry/parking fields without replacing receipt rows. |
| V15 | Adds the successful recovery quota ledger; older completions cannot be reconstructed. |
| V16 | Adds an independent encrypted recovery outbox with lease and expiry constraints. |
| V17 | Separates persistent password audit from disposable confirmation delivery receipts. |
| V18 | Adds successful login audit and cursor indexes for both histories. Its applied checksum remains `22842503`. |
| V19 | Removes audit-to-workspace FK locks through a forward migration; retains rows, receipt ownership and original migration history. |
| V20 | Adds pending/verified owner status and independent activation token/outbox tables; preserves older accounts, sessions and migration checksums. |

Repeated `ALTER TABLE` statements express transitions from older populated
schemas. Folding them into the original `CREATE TABLE`, renumbering files or
replacing V1–V20 with one migration would invalidate already-applied history.
Development installations with a persistent database have the same constraint.

## Compose and development databases

`compose.yaml` provisions one PostgreSQL 18 database named `android`. The named
`postgres-data` volume retains its schema, rows and Flyway history across normal
container recreation. The backend executes Flyway on startup; PostgreSQL itself
does not replay application migrations or require one database per table.
`POSTGRES_DB` and `POSTGRES_USER` initialize an empty data directory and do not
rename databases/accounts already stored in the volume.

Existing installations should keep the V1–V20 files unchanged and use a new
version for further schema changes. A fresh disposable database can apply the
entire existing chain. A consolidated development baseline would be a separate
workflow for an explicitly new empty database, with schema/seed equivalence
checks; it is not implemented by this review and must not replace the history
of an existing installation. Changing migration organization does not require
a volume reset, checksum repair or validation bypass.

See [operations](operations.md#database-migrations-and-upgrades) for populated
upgrade preflight and [disposable testing](operations.md#testing).

## Compatibility verification

`BookReadingMigrationTest` covers fresh migration/validation, repeated startup
with no pending work, populated upgrades, stable book/document/progress references,
historical event rewrite, duplicate-pair rejection, private/public isolation,
queued email preservation and the applied-V18-to-V19 forward fix. Feature tests
exercise authorization, quotas, retries, rollback and concurrency against the
resulting schema. Tests use disposable PostgreSQL/RabbitMQ rather than the
persistent development volume.
