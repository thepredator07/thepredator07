-- Personal AI Career Agent: PostgreSQL schema (all modules).
-- Module A (job discovery) uses: settings, companies, job_sources, discovery_runs, jobs, job_scores, execution_log.
-- Later modules (B-H) tables are created now so foreign keys and dashboards are stable from day one.
-- Idempotent: safe to re-run.

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------------------------
-- Configuration (read by workflows at run time; no secrets here, secrets live in n8n credentials)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS settings (
    key         text PRIMARY KEY,
    value       jsonb NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Companies and where their jobs come from
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS companies (
    id               serial PRIMARY KEY,
    name             text NOT NULL,
    normalized_name  text GENERATED ALWAYS AS (lower(regexp_replace(name, '[^a-zA-Z0-9]+', '', 'g'))) STORED,
    category         text NOT NULL DEFAULT 'unknown'
                     CHECK (category IN ('product','fintech','saas_cloud','engineering_led','services_consulting','unknown')),
    priority         smallint NOT NULL DEFAULT 3 CHECK (priority BETWEEN 1 AND 5),   -- 1 = dream company
    is_target        boolean NOT NULL DEFAULT true,
    careers_url      text,
    linkedin_url     text,
    notes            text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (normalized_name)
);

CREATE TABLE IF NOT EXISTS job_sources (
    id                    serial PRIMARY KEY,
    company_id            int NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    ats                   text NOT NULL CHECK (ats IN ('greenhouse','lever','ashby','smartrecruiters','workday')),
    board_token           text NOT NULL,          -- greenhouse board token / lever site / ashby org / SR company id / workday "host|tenant|site"
    endpoint_url          text,                   -- optional override (EU Lever, tests, mocks)
    search_text           text,                   -- workday only: server-side search text
    is_active             boolean NOT NULL DEFAULT true,
    verified              boolean NOT NULL DEFAULT false,   -- true after first successful fetch
    consecutive_failures  int NOT NULL DEFAULT 0,
    last_success_at       timestamptz,
    last_status           int,
    last_error            text,
    created_at            timestamptz NOT NULL DEFAULT now(),
    UNIQUE (ats, board_token)
);

-- ---------------------------------------------------------------------------
-- Module A: discovery runs, jobs, scores
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS discovery_runs (
    id               serial PRIMARY KEY,
    started_at       timestamptz NOT NULL DEFAULT now(),
    finished_at      timestamptz,
    status           text NOT NULL DEFAULT 'running' CHECK (status IN ('running','succeeded','partial','failed')),
    n8n_execution_id text,
    sources_ok       int DEFAULT 0,
    sources_failed   int DEFAULT 0,
    jobs_fetched     int DEFAULT 0,
    jobs_prefiltered int DEFAULT 0,
    jobs_new         int DEFAULT 0,
    jobs_duplicates  int DEFAULT 0,
    jobs_scored      int DEFAULT 0,
    jobs_score_failed int DEFAULT 0,
    jobs_expired     int DEFAULT 0,
    source_results   jsonb,
    error            text
);

CREATE TABLE IF NOT EXISTS jobs (
    id                 bigserial PRIMARY KEY,
    source_id          int NOT NULL REFERENCES job_sources(id) ON DELETE CASCADE,
    company_id         int NOT NULL REFERENCES companies(id) ON DELETE CASCADE,
    external_id        text NOT NULL,
    fingerprint        text NOT NULL,           -- company|title|city, for cross-source de-duplication
    title              text NOT NULL,
    location_raw       text,
    matched_location   text,                    -- mumbai | bengaluru | hyderabad | pune | remote_india | null
    is_remote          boolean NOT NULL DEFAULT false,
    department         text,
    employment_type    text,
    description_text   text,                    -- stored only for jobs that pass the pre-filter
    exp_min            smallint,
    exp_max            smallint,
    exp_text           text,
    exp_fit            text CHECK (exp_fit IN ('fit','stretch','over','under','unknown')),
    exp_in_2_5         boolean,                 -- requirement overlaps 2-5 years
    salary_min_inr     bigint,                  -- ONLY from structured ATS data or verbatim JD text; never estimated
    salary_max_inr     bigint,
    salary_currency    text,
    salary_interval    text,
    salary_source      text NOT NULL DEFAULT 'none' CHECK (salary_source IN ('structured','jd_text','none')),
    salary_text        text,
    comp_fit           text NOT NULL DEFAULT 'unknown' CHECK (comp_fit IN ('fit','above','below','unknown','non_inr')),
    posted_at          timestamptz,
    job_url            text NOT NULL,
    apply_url          text,
    prefilter_pass     boolean NOT NULL,
    prefilter_reason   text,
    duplicate_of       bigint REFERENCES jobs(id),
    first_seen_run_id  int REFERENCES discovery_runs(id),
    first_seen_at      timestamptz NOT NULL DEFAULT now(),
    last_seen_at       timestamptz NOT NULL DEFAULT now(),
    is_active          boolean NOT NULL DEFAULT true,
    expired_at         timestamptz,
    status             text NOT NULL DEFAULT 'discovered'
                       CHECK (status IN ('discovered','shortlisted','dismissed','applied')),
    UNIQUE (source_id, external_id)
);
CREATE INDEX IF NOT EXISTS jobs_fingerprint_idx ON jobs (fingerprint);
CREATE INDEX IF NOT EXISTS jobs_active_idx ON jobs (is_active, prefilter_pass) WHERE duplicate_of IS NULL;

CREATE TABLE IF NOT EXISTS job_scores (
    id               bigserial PRIMARY KEY,
    job_id           bigint NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    prompt_version   text NOT NULL,
    model            text NOT NULL,
    total            smallint NOT NULL CHECK (total BETWEEN 0 AND 100),
    technical        smallint NOT NULL CHECK (technical BETWEEN 0 AND 40),
    experience       smallint NOT NULL CHECK (experience BETWEEN 0 AND 20),
    location         smallint NOT NULL CHECK (location BETWEEN 0 AND 15),
    company          smallint NOT NULL CHECK (company BETWEEN 0 AND 15),
    compensation     smallint NOT NULL CHECK (compensation BETWEEN 0 AND 10),
    verdict          text NOT NULL,
    company_type     text,
    matched_skills   text[],
    missing_skills   text[],
    red_flags        text[],
    summary          text,
    raw_response     jsonb,
    input_tokens     int,
    output_tokens    int,
    cache_read_tokens int,
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (job_id, prompt_version)
);

-- ---------------------------------------------------------------------------
-- Cross-cutting: execution log (every workflow writes here; the error workflow too)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS execution_log (
    id            bigserial PRIMARY KEY,
    ts            timestamptz NOT NULL DEFAULT now(),
    workflow      text NOT NULL,
    execution_id  text,
    node          text,
    level         text NOT NULL CHECK (level IN ('debug','info','warn','error')),
    message       text NOT NULL,
    context       jsonb
);
CREATE INDEX IF NOT EXISTS execution_log_ts_idx ON execution_log (ts DESC);

-- ---------------------------------------------------------------------------
-- Module B / D: people, outreach, referrals
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS people (
    id                   bigserial PRIMARY KEY,
    company_id           int REFERENCES companies(id),
    full_name            text NOT NULL,
    designation          text,
    location             text,
    linkedin_url         text UNIQUE,            -- public profile URL, user-provided or from a permitted source
    role_bucket          text CHECK (role_bucket IN ('engineering_manager','backend_lead','technical_recruiter','talent_acquisition','engineer','other')),
    professional_relevance text,
    referral_relevance   smallint CHECK (referral_relevance BETWEEN 0 AND 10),
    connection_status    text NOT NULL DEFAULT 'not_connected'
                         CHECK (connection_status IN ('not_connected','request_drafted','request_sent','connected','declined')),
    connected_at         date,
    last_interaction_at  timestamptz,
    source               text NOT NULL DEFAULT 'manual',
    notes                text,
    created_at           timestamptz NOT NULL DEFAULT now()
);

-- Every external message is a draft until a human approves AND sends it.
CREATE TABLE IF NOT EXISTS outreach_messages (
    id            bigserial PRIMARY KEY,
    person_id     bigint REFERENCES people(id) ON DELETE CASCADE,
    channel       text NOT NULL CHECK (channel IN ('linkedin_connect','linkedin_message','email')),
    purpose       text NOT NULL CHECK (purpose IN ('connect','follow_up','referral_request','thank_you','recruiter_reply','other')),
    body          text NOT NULL,
    status        text NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','approved','sent','discarded')),
    generated_by  text,
    approved_at   timestamptz,
    sent_at       timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);
-- Duplicate-outreach guard: one live connect request per person.
CREATE UNIQUE INDEX IF NOT EXISTS outreach_one_connect_per_person
    ON outreach_messages (person_id) WHERE channel = 'linkedin_connect' AND status IN ('draft','approved','sent');

CREATE TABLE IF NOT EXISTS referrals (
    id                  bigserial PRIMARY KEY,
    company_id          int NOT NULL REFERENCES companies(id),
    person_id           bigint NOT NULL REFERENCES people(id),
    job_id              bigint REFERENCES jobs(id),
    position            text,
    connection_date     date,
    response_received   boolean NOT NULL DEFAULT false,
    referral_requested_at timestamptz,
    referral_submitted_at timestamptz,
    job_application_link text,
    follow_up_count     smallint NOT NULL DEFAULT 0 CHECK (follow_up_count <= 2),  -- hard cap on follow-ups
    next_follow_up_date date,
    status              text NOT NULL DEFAULT 'open' CHECK (status IN ('open','submitted','declined','no_response','closed')),
    notes               text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (person_id, job_id)
);

-- ---------------------------------------------------------------------------
-- Module E: applications (+ Module F resume versions)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS resume_versions (
    id            serial PRIMARY KEY,
    label         text NOT NULL UNIQUE,         -- e.g. base-v3, razorpay-backend-2026-11
    is_base       boolean NOT NULL DEFAULT false,
    storage_url   text,                         -- Google Drive / file path
    content_text  text,
    approved_at   timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS applications (
    id                 bigserial PRIMARY KEY,
    company_id         int NOT NULL REFERENCES companies(id),
    job_id             bigint REFERENCES jobs(id),
    external_job_id    text,
    role               text NOT NULL,
    location           text,
    job_url            text,
    date_discovered    date,
    match_score        smallint,
    status             text NOT NULL DEFAULT 'discovered' CHECK (status IN (
                        'discovered','shortlisted','referral_pending','ready_to_apply','applied',
                        'recruiter_screening','technical_interview','managerial_interview','hr_round',
                        'offer','rejected','on_hold')),
    resume_version_id  int REFERENCES resume_versions(id),
    referral_id        bigint REFERENCES referrals(id),
    recruiter_person_id bigint REFERENCES people(id),
    applied_date       date,
    interview_rounds   smallint NOT NULL DEFAULT 0,
    feedback           text,
    expected_ctc_lpa   numeric(5,2),
    follow_up_date     date,
    notes              text,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
-- Duplicate-application guard.
CREATE UNIQUE INDEX IF NOT EXISTS applications_one_per_job ON applications (job_id) WHERE job_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS application_events (
    id              bigserial PRIMARY KEY,
    application_id  bigint NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    from_status     text,
    to_status       text NOT NULL,
    ts              timestamptz NOT NULL DEFAULT now(),
    note            text
);

-- ---------------------------------------------------------------------------
-- Module C / G: recruiter email and interviews
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS email_threads (
    id                bigserial PRIMARY KEY,
    gmail_thread_id   text NOT NULL UNIQUE,
    gmail_last_message_id text,
    company_id        int REFERENCES companies(id),
    application_id    bigint REFERENCES applications(id),
    recruiter_person_id bigint REFERENCES people(id),
    category          text CHECK (category IN ('new_opportunity','interview_invitation','hr_follow_up','rejection','offer','referral','general')),
    subject           text,
    summary           text,
    extracted         jsonb,                    -- company, designation, salary, location, dates, questions
    resume_requested  boolean NOT NULL DEFAULT false,
    draft_gmail_id    text,                     -- Gmail draft created for approval; never auto-sent
    needs_action      boolean NOT NULL DEFAULT true,
    last_message_at   timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS interviews (
    id                bigserial PRIMARY KEY,
    application_id    bigint REFERENCES applications(id),
    email_thread_id   bigint REFERENCES email_threads(id),
    round             text,
    scheduled_at      timestamptz,
    duration_minutes  int,
    meeting_link      text,
    calendar_event_id text,                     -- set only after approval
    prep_doc_url      text,
    status            text NOT NULL DEFAULT 'detected' CHECK (status IN ('detected','approved','scheduled','completed','cancelled')),
    created_at        timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Generic approval queue (every external action goes through here)
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS approvals (
    id            bigserial PRIMARY KEY,
    kind          text NOT NULL,                -- send_email, linkedin_message, apply_job, calendar_event, tailored_resume
    ref_table     text NOT NULL,
    ref_id        bigint NOT NULL,
    payload       jsonb,
    status        text NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','approved','rejected','expired')),
    decided_at    timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (kind, ref_table, ref_id)
);

-- ---------------------------------------------------------------------------
-- Views used by Module A checks and the daily brief
-- ---------------------------------------------------------------------------
CREATE OR REPLACE VIEW v_job_matches AS
SELECT j.id, c.name AS company, c.category, j.title, j.matched_location, j.location_raw,
       j.exp_min, j.exp_max, j.exp_fit, j.comp_fit, j.salary_text,
       s.total AS score, s.technical, s.experience, s.location, s.company AS company_pts, s.compensation,
       s.verdict, s.summary, s.matched_skills, s.missing_skills, s.red_flags,
       j.posted_at, j.first_seen_at, j.job_url, j.apply_url, j.status, j.is_active
FROM jobs j
JOIN companies c ON c.id = j.company_id
LEFT JOIN LATERAL (
    SELECT * FROM job_scores js WHERE js.job_id = j.id ORDER BY js.created_at DESC LIMIT 1
) s ON true
WHERE j.prefilter_pass AND j.duplicate_of IS NULL;

COMMIT;
