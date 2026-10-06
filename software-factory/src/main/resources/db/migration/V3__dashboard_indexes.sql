-- Dashboard pages filter by state and sort newest first (M6 paging).
CREATE INDEX ix_tickets_state_created ON tickets (state, created_at DESC, id DESC);
CREATE INDEX ix_tickets_created_id ON tickets (created_at DESC, id DESC);
DROP INDEX ix_tickets_created_at;
