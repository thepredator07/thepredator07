-- M1: a ticket is now one *attempt* at an issue. An issue can be attempted again after its last attempt finished,
-- when someone re-applies the trigger label. Each attempt snapshots the issue title and body at trigger time.

ALTER TABLE tickets ADD COLUMN attempt INTEGER NOT NULL DEFAULT 1;
-- When the trigger label was applied for this attempt. Existing rows: the time the ticket was created.
ALTER TABLE tickets ADD COLUMN triggered_at TIMESTAMPTZ;
UPDATE tickets SET triggered_at = created_at;
ALTER TABLE tickets ALTER COLUMN triggered_at SET NOT NULL;

ALTER TABLE tickets DROP CONSTRAINT uq_tickets_repo_issue;
ALTER TABLE tickets ADD CONSTRAINT uq_tickets_repo_issue_attempt UNIQUE (repo, issue_number, attempt);

-- At most one unfinished attempt per issue.
CREATE UNIQUE INDEX uq_tickets_one_active_per_issue ON tickets (repo, issue_number)
    WHERE state NOT IN ('DONE','FAILED','CANCELLED');

CREATE INDEX ix_tickets_issue ON tickets (repo, issue_number, attempt DESC);
