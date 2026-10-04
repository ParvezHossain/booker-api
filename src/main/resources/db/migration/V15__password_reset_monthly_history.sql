-- Successful recovery only: email issuance and authenticated password changes do not count.
-- Earlier reset tokens contain no historical completion data; existing accounts start at zero.
CREATE TABLE password_reset_history (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email VARCHAR(254) NOT NULL REFERENCES workspace_users(email) ON DELETE CASCADE,
    reset_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX password_reset_history_account_month ON password_reset_history(email, reset_at);
