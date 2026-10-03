# Backend design and documentation review

Review date: 2026-09-30. Scope: this Spring Boot backend, all application controller mappings, account/JWT/password security, private/public books, PDF validation/storage, reading progress, notifications, Google Drive, repositories/DTOs, configuration, Flyway migrations, API references and tests. Native Android source is not in this repository; the adjacent Angular application is outside this refactor.

## Architecture and decisions

The existing controller → service → JPA/JDBC structure is retained. PostgreSQL/Flyway owns relational invariants. Services own authorization, transaction boundaries and workflows. `FileStorageService` separates document workflows from byte storage. Controllers remain HTTP adapters; immutable response records omit storage paths and Google refresh secrets.

| Principle | Concrete application |
|---|---|
| Single responsibility | `WorkspaceController` moved out of `WorkspaceAccounts`; `GoogleDriveConfiguration` moved out of `GoogleDriveSettings`. `PasswordService` owns token lifecycle; `SmtpPasswordResetDelivery` owns email configuration, formatting and transport. |
| Open/closed and dependency inversion | Password recovery depends on the small `PasswordResetDelivery` interface. Another delivery adapter can implement that contract without rewriting password workflows. Existing `FileStorageService` remains the storage boundary. |
| Interface segregation and substitution | Delivery exposes only readiness and sending, with a documented provider-acceptance/failure contract. Existing storage implementations retain streaming, opaque-key and idempotent-delete requirements. No unrelated methods or speculative generic interfaces were added. |
| DRY | `BookMapper.applyMetadata` is shared by private create and public create/update. `OpaqueTokens` centralizes random secrets and SHA-256 for reset, refresh and OAuth state/PKCE. Authorized document lookups share active-document resolution. |
| KISS | Existing services, request/response JSON, routes, role/workspace scopes, JDBC/JPA persistence, transaction boundaries and migrations are preserved. No generic CRUD framework, new dependency or architecture replacement was introduced by this refactor. |

`OpaqueTokens` is for high-entropy bearer secrets. User passwords continue to use salted PBKDF2 through the existing encoder. File checksums still use streaming digest updates rather than buffering PDF content into a string utility.

## Comments and invariants

Comments explain decisions that would otherwise be easy to break:

- Account locks serialize token issuance with password replacement; pre-V11 JWTs are treated as credential version zero.
- Metadata mapping never copies client ownership or library scope.
- PDF parsing happens outside database transactions, keeping locks short.
- Progress SQL identifiers come only from a closed scope enum; private-account and public-workspace records remain separate.
- PDF object traversal handles cycles and bounds work; local file publication is atomic.
- Google credentials are authenticated against their account using AES-GCM additional data.
- Failed SMTP delivery invalidates the token but retains its timestamp for the cooldown; provider exception text and reset secrets are not logged.

## Swagger coverage

Login, refresh, logout and the retained root mapping now have explicit operation documentation. Password request schemas mark credentials and reset tokens write-only. The root mapping stays denied by security; its documentation identifies it as a disabled legacy route. Public Actuator health is documented programmatically because its handler is provided by Spring.

Several controller-level error annotations previously suppressed success responses or caused errors to inherit success DTOs. Explicit success schemas and ApiError schemas correct those contracts. Private/public PDF GET responses are `application/pdf` binary, document Range headers and 206/416 behavior; HEAD success and errors have no response body. Both reading-progress 409 shapes remain documented. Drive operations include status codes, authentication, scopes, callback behavior and request constraints.

`OpenApiCoverageTest` compares generated OpenAPI with Spring's application handler registry. It checks all 38 business operations, explicit summaries/tags, success status codes, public/protected security, password schema secrecy, PDF Range/binary/HEAD behavior, upload error schemas, and both progress conflict bodies. Framework Swagger assets are documentation infrastructure rather than additional business operations.

## Maintenance guidance

Add shared logic where behavior is actually identical. Private quotas, Super Admin management and workspace/user progress ownership have different rules and remain explicit. Preserve account/book lock order and retry identity when changing transactional code. Extend the storage/delivery boundaries for new providers rather than embedding provider APIs in business workflows.

SMTP remains synchronous as before: sending can hold the account transaction for the configured timeout. A durable mail outbox would be a separate reliability feature with its own migration and delivery semantics. Local storage and external-service credentials remain deployment choices described in the feature guides. This review does not verify a live SMTP/Google account or frontend behavior.

## Verification

Final Maven `package` verification passed against an isolated PostgreSQL 18 database: **105 tests, 0 failures, 0 errors, 0 skipped**. This includes private/public library isolation, upload/streaming/Range limits, quotas, progress concurrency/idempotency, notification replay, password revocation/recovery, SMTP adapter behavior, Google OAuth/imports and migration regressions. Four new OpenAPI coverage tests and two SMTP adapter tests protect the refactored boundaries. Generated Swagger was inspected alongside the handler registry. `git diff --check` passed. V1–V10 were compared byte-for-byte with committed migrations; V11 keeps the earlier password feature schema. No configured lint/static-analysis plugin or frontend suite was added or run.
