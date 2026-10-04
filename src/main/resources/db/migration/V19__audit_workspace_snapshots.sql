-- Book requests lock workspace -> account. Account-locked login/password audit
-- must not acquire the reverse order through workspace foreign keys.
-- Keep captured workspace UUIDs and all audit/receipt data as event-time snapshots.
-- Account foreign keys and their deletion cascades remain unchanged.
ALTER TABLE login_history DROP CONSTRAINT login_history_workspace_id_fkey;
ALTER TABLE password_change_history DROP CONSTRAINT password_change_history_workspace_id_fkey;
