-- Keep PostgreSQL authoritative while RabbitMQ carries only opaque receipt IDs.
ALTER TABLE public_request_emails
    ADD COLUMN id UUID NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD COLUMN available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    ADD COLUMN failed_at TIMESTAMPTZ;
ALTER TABLE public_request_emails ADD CONSTRAINT public_request_emails_id UNIQUE(id);
CREATE INDEX public_request_emails_dispatch ON public_request_emails(available_at, created_at)
    WHERE failed_at IS NULL;
