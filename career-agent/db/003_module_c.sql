-- Module C: Gmail recruiter assistant. Idempotent migration on top of 001/002.
BEGIN;

-- Recruiters are tracked as people, keyed by email.
ALTER TABLE people ADD COLUMN IF NOT EXISTS email text;
ALTER TABLE people ADD COLUMN IF NOT EXISTS agency text;
ALTER TABLE people ADD COLUMN IF NOT EXISTS phone text;
CREATE UNIQUE INDEX IF NOT EXISTS people_email_uniq ON people (lower(email)) WHERE email IS NOT NULL;

-- One row per Gmail message the assistant has looked at. Bodies are kept only for job-related mail.
CREATE TABLE IF NOT EXISTS email_messages (
    id                bigserial PRIMARY KEY,
    gmail_message_id  text NOT NULL UNIQUE,
    gmail_thread_id   text NOT NULL,
    email_thread_id   bigint REFERENCES email_threads(id),
    received_at       timestamptz,
    from_email        text,
    from_name         text,
    subject           text,
    relevance         text NOT NULL CHECK (relevance IN ('recruiter','job_alert','skipped','not_job_related')),
    triage_reason     text,
    status            text NOT NULL CHECK (status IN ('done','skipped','error')),
    attempts          smallint NOT NULL DEFAULT 0,
    last_error        text,
    category          text,
    body_excerpt      text,
    analysis          jsonb,
    model             text,
    input_tokens      int,
    output_tokens     int,
    processed_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS email_messages_thread_idx ON email_messages (gmail_thread_id);

ALTER TABLE email_threads ADD COLUMN IF NOT EXISTS suggested_application_status text;
ALTER TABLE email_threads ADD COLUMN IF NOT EXISTS needs_user_input text[];
ALTER TABLE email_threads ADD COLUMN IF NOT EXISTS urgency text;
ALTER TABLE email_threads ADD COLUMN IF NOT EXISTS message_count int NOT NULL DEFAULT 0;
ALTER TABLE email_threads ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

-- Reply drafts. Created in Gmail Drafts only; the assistant has no code path that sends mail.
CREATE TABLE IF NOT EXISTS email_drafts (
    id                   bigserial PRIMARY KEY,
    email_thread_id      bigint REFERENCES email_threads(id),
    gmail_thread_id      text NOT NULL,
    reply_to_message_id  text NOT NULL UNIQUE,      -- one draft per inbound message
    to_email             text NOT NULL,
    subject              text NOT NULL,
    body                 text NOT NULL,
    raw                  text NOT NULL,             -- base64url RFC 2822, as sent to drafts.create
    needs_user_input     text[],
    status               text NOT NULL DEFAULT 'pending'
                         CHECK (status IN ('pending','creating','created','failed','superseded')),
    attempts             smallint NOT NULL DEFAULT 0,
    gmail_draft_id       text,
    last_error           text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE interviews ADD COLUMN IF NOT EXISTS source_text text;
ALTER TABLE interviews ADD COLUMN IF NOT EXISTS time_assumed_ist boolean NOT NULL DEFAULT false;
ALTER TABLE interviews ADD COLUMN IF NOT EXISTS mode text;
CREATE UNIQUE INDEX IF NOT EXISTS interviews_thread_time ON interviews (email_thread_id, scheduled_at)
    WHERE scheduled_at IS NOT NULL;

-- Outbound notifications to you (Telegram). Priority 1 = now, 2 = soon, 3 = daily brief only.
CREATE TABLE IF NOT EXISTS notifications (
    id          bigserial PRIMARY KEY,
    kind        text NOT NULL,
    priority    smallint NOT NULL CHECK (priority BETWEEN 1 AND 3),
    title       text NOT NULL,
    body        text,
    ref_table   text,
    ref_id      bigint,
    dedupe_key  text NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    sent_at     timestamptz,
    attempts    smallint NOT NULL DEFAULT 0,
    last_error  text
);
CREATE INDEX IF NOT EXISTS notifications_unsent_idx ON notifications (priority, created_at) WHERE sent_at IS NULL;

-- Simple lease so a slow run and the next scheduled run never process the same mail twice.
CREATE TABLE IF NOT EXISTS workflow_locks (
    name          text PRIMARY KEY,
    holder        text,
    locked_until  timestamptz NOT NULL
);

INSERT INTO settings (key, value) VALUES
('gmail', $json${
  "gmail_base_url": "https://gmail.googleapis.com",
  "query": "newer_than:3d -from:me -in:chats -in:spam -in:trash",
  "max_list": 100,
  "max_messages_per_run": 25,
  "max_body_chars": 12000,
  "max_attempts": 3,
  "lock_minutes": 14,
  "model": "claude-opus-5-5",
  "effort": "medium",
  "use_fallbacks": true,
  "prompt_version": "email-v1",
  "create_drafts": true,
  "signature": "Best regards,\nKhubaib",
  "reply_facts": {
    "name": "Khubaib",
    "notice_period": "60 days, negotiable to 45 days",
    "current_ctc": null,
    "expected_ctc": null,
    "availability_for_calls": null,
    "open_to_relocation": "Mumbai, Bengaluru, Hyderabad, Pune or remote"
  },
  "ats_sender_domains": ["greenhouse.io","greenhouse-mail.io","lever.co","hire.lever.co","ashbyhq.com","myworkday.com","myworkdayjobs.com","smartrecruiters.com","smartrecruiters-mail.com","workablemail.com","workable.com","icims.com","successfactors.com","taleo.net","hackerrank.com","hackerearth.com","codility.com","mettl.com","naukri.com","instahyre.com","iimjobs.com","hirist.tech","hirist.com","cutshort.io","wellfound.com","angel.co","foundit.in","zohorecruit.com","freshteam.com","darwinbox.in","keka.com"],
  "job_platform_domains": ["linkedin.com","naukri.com","indeed.com","wellfound.com","instahyre.com","iimjobs.com","hirist.tech","cutshort.io","foundit.in","glassdoor.com","monster.com","glassdoor.co.in"],
  "job_alert_sender_regex": "(job.?alert|jobalerts|jobs-listings|jobs-noreply|alert@|alerts@|recommendations?@)",
  "job_alert_subject_regex": "(job alert|jobs? for you|new jobs?\\b|jobs matching|recommended jobs|jobs you may|is hiring|are hiring|apply now)",
  "recruiting_keywords_regex": "(opportunit(y|ies)|opening|position|\\brole\\b|vacanc|hiring|interview|recruit|talent acquisition|\\bjd\\b|job description|\\bctc\\b|notice period|resume|\\bcv\\b|your application|application (for|status|received|update)|offer letter|job offer|offer of employment|referr(al|ed)|shortlist|assessment|coding (test|round|challenge)|screening|candidature|background verification|joining date)"
}$json$::jsonb)
ON CONFLICT (key) DO NOTHING;

COMMIT;
