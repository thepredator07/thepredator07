-- Tickets: one per GitHub issue the factory picked up.
CREATE TABLE tickets (
    id              BIGSERIAL PRIMARY KEY,
    repo            VARCHAR(200)  NOT NULL,
    issue_number    INTEGER       NOT NULL,
    title           VARCHAR(500)  NOT NULL,
    body            TEXT          NOT NULL DEFAULT '',
    state           VARCHAR(32)   NOT NULL,
    branch_name     VARCHAR(200),
    sandbox_id      VARCHAR(200),
    pr_number       INTEGER,
    pr_url          VARCHAR(500),
    last_feedback   TEXT,
    failure_reason  TEXT,
    tokens_input    BIGINT        NOT NULL DEFAULT 0,
    tokens_output   BIGINT        NOT NULL DEFAULT 0,
    cost_usd        NUMERIC(12,4) NOT NULL DEFAULT 0,
    turns           INTEGER       NOT NULL DEFAULT 0,
    retries         INTEGER       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    duration_ms     BIGINT,
    CONSTRAINT uq_tickets_repo_issue UNIQUE (repo, issue_number),
    CONSTRAINT ck_tickets_state CHECK (state IN ('RECEIVED','SANDBOX_READY','CODING','CHECKS',
        'PR_OPENED','AWAITING_APPROVAL','DONE','FAILED','CANCELLED'))
);

CREATE INDEX ix_tickets_state ON tickets (state);
CREATE INDEX ix_tickets_created_at ON tickets (created_at DESC);

-- Append-only audit log of every state transition.
CREATE TABLE ticket_transitions (
    id          BIGSERIAL PRIMARY KEY,
    ticket_id   BIGINT       NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    from_state  VARCHAR(32),
    to_state    VARCHAR(32)  NOT NULL,
    reason      TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_transitions_ticket ON ticket_transitions (ticket_id, id);

-- Job queue. Workers claim rows with SELECT ... FOR UPDATE SKIP LOCKED.
CREATE TABLE jobs (
    id          BIGSERIAL PRIMARY KEY,
    ticket_id   BIGINT       NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts    INTEGER      NOT NULL DEFAULT 0,
    run_after   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    locked_by   VARCHAR(100),
    locked_at   TIMESTAMPTZ,
    last_error  TEXT,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_jobs_status CHECK (status IN ('PENDING','RUNNING','DONE','FAILED'))
);

CREATE INDEX ix_jobs_claim ON jobs (run_after, id) WHERE status = 'PENDING';
-- At most one live job per ticket, so a ticket can never be processed twice at once.
CREATE UNIQUE INDEX uq_jobs_one_active_per_ticket ON jobs (ticket_id) WHERE status IN ('PENDING','RUNNING');
