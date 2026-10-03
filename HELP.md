# Engineering documentation index

Use [README.md](README.md) for local startup and project scope. Detailed documents
have distinct responsibilities so setup, API contracts and proposed client work
remain consistent.

| Document | Purpose |
| --- | --- |
| [API.md](API.md) | Implemented HTTP methods, parameters, validation, schemas, errors and client workflows |
| [AGENTS.md](AGENTS.md) | Repository contribution rules and definition of done |
| [Architecture](docs/code-quality-review.md) | Package boundaries, persistence, locks and invariants |
| [Operations](docs/operations.md) | Configuration, database/test setup, migrations, backups, deployment and troubleshooting |
| [PDF reading](docs/book-reading.md) | Immutable documents, storage, version conflicts, progress and Google Drive |
| [Public library](docs/public-library.md) | Global catalogue, Super Admin and workspace-shared reading |
| [RabbitMQ email delivery](docs/request-email-queue.md) | Bounded queue, retries, quarantine and recovery |
| [Book requests](docs/public-library-requests.md) | Review transactions, notifications and email receipts |
| [Password management](docs/password-management.md) | Reset tokens, revocation, SMTP and security constraints |
| [Notifications](docs/book-notifications.md) | SSE framing, replay, cursor ordering and operations |
| [Engineering backlog](PROMPTS.md) | Proposed improvements with acceptance criteria |
| [Android specification](ANDROID_PROMPTS.md) | Client transport, durable offline state, reader and integration requirements |
| [Angular specification](ANGULAR_PROMPTS.md) | Client implementation requirements and release checks |
| [Angular rules](ANGULAR_AGENTS.md) | Contribution rules for the separately maintained client |

No frontend implementation
or live external-service verification is established by these client specifications.
