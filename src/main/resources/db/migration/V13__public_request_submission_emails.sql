-- Preserve queued V12 decision emails while supporting separate admin receipts.
ALTER TABLE public_request_emails
    ADD COLUMN email_type VARCHAR(20) NOT NULL DEFAULT 'DECISION'
        CHECK (email_type IN ('DECISION', 'SUBMISSION')),
    ADD COLUMN subject VARCHAR(255) NOT NULL DEFAULT 'Your Booker public library request',
    ADD COLUMN html_message TEXT;

ALTER TABLE public_request_emails DROP CONSTRAINT public_request_emails_pkey;
ALTER TABLE public_request_emails ADD PRIMARY KEY (request_id, email_type, recipient);
CREATE INDEX public_request_emails_delivery_order ON public_request_emails(created_at);
