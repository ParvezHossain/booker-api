CREATE TABLE public_library_book_requests (
 id UUID PRIMARY KEY,
 title VARCHAR(255) NOT NULL,
 author_name VARCHAR(255) NOT NULL,
 workspace_id UUID NOT NULL REFERENCES workspaces(id),
 requester_email VARCHAR(254) NOT NULL REFERENCES workspace_users(email),
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','ACCEPTED','REJECTED')),
 book_id BIGINT REFERENCES books(id) ON DELETE SET NULL,
 reviewed_by VARCHAR(254) REFERENCES workspace_users(email),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 reviewed_at TIMESTAMPTZ,
 CHECK ((status = 'PENDING' AND reviewed_at IS NULL AND reviewed_by IS NULL) OR
        (status <> 'PENDING' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL))
);
CREATE INDEX public_requests_workspace ON public_library_book_requests(workspace_id, created_at);
CREATE INDEX public_requests_status ON public_library_book_requests(status, created_at);
CREATE UNIQUE INDEX public_requests_pending_pair ON public_library_book_requests(workspace_id, title, author_name) WHERE status = 'PENDING';
ALTER TABLE book_events ADD COLUMN event_type VARCHAR(80) NOT NULL DEFAULT 'book.created';
CREATE TABLE public_request_emails (
 request_id UUID PRIMARY KEY REFERENCES public_library_book_requests(id),
 recipient VARCHAR(254) NOT NULL,
 message TEXT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
