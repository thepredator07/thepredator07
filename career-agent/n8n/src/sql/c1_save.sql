-- $1 records, $2 threads, $3 people, $4 interviews, $5 drafts, $6 max attempts, $7 notifications,
-- $8 execution id, $9 log message, $10 stats
INSERT INTO companies (name, category, is_target)
SELECT DISTINCT ON (lower(regexp_replace(t.company, '[^a-zA-Z0-9]+', '', 'g'))) t.company, 'unknown', false
FROM jsonb_to_recordset($2::jsonb) AS t(company text)
WHERE t.company IS NOT NULL AND regexp_replace(t.company, '[^a-zA-Z0-9]+', '', 'g') <> ''
ON CONFLICT (normalized_name) DO NOTHING;

INSERT INTO people (full_name, designation, email, agency, phone, company_id, role_bucket, source, last_interaction_at)
SELECT COALESCE(p.full_name, p.email), p.designation, lower(p.email), p.agency, p.phone,
       (SELECT c.id FROM companies c WHERE c.normalized_name = lower(regexp_replace(p.company, '[^a-zA-Z0-9]+', '', 'g'))),
       CASE WHEN p.designation ~* '(talent|human|\mhr\M|people)' THEN 'talent_acquisition' ELSE 'technical_recruiter' END,
       'gmail', p.last_interaction_at
FROM jsonb_to_recordset($3::jsonb) AS p(email text, full_name text, designation text, company text, agency text, phone text,
                                         last_interaction_at timestamptz)
ON CONFLICT ((lower(email))) WHERE email IS NOT NULL DO UPDATE SET
  last_interaction_at = GREATEST(people.last_interaction_at, EXCLUDED.last_interaction_at),
  designation = COALESCE(people.designation, EXCLUDED.designation),
  company_id  = COALESCE(people.company_id, EXCLUDED.company_id),
  agency      = COALESCE(people.agency, EXCLUDED.agency),
  phone       = COALESCE(people.phone, EXCLUDED.phone);

INSERT INTO email_threads (gmail_thread_id, gmail_last_message_id, company_id, application_id, recruiter_person_id, category,
                           subject, summary, extracted, resume_requested, needs_action, last_message_at,
                           suggested_application_status, needs_user_input, urgency, message_count, updated_at)
SELECT t.gmail_thread_id, t.gmail_last_message_id, co.id,
       (SELECT a.id FROM applications a WHERE a.company_id = co.id AND a.status <> 'rejected' ORDER BY a.updated_at DESC LIMIT 1),
       (SELECT p.id FROM people p WHERE lower(p.email) = lower(t.recruiter_email)),
       t.category, t.subject, t.summary, t.extracted, t.resume_requested, t.needs_action, t.last_message_at,
       t.suggested_application_status, ARRAY(SELECT jsonb_array_elements_text(t.needs_user_input)), t.urgency, t.new_messages, now()
FROM jsonb_to_recordset($2::jsonb) AS t(gmail_thread_id text, gmail_last_message_id text, company text, recruiter_email text,
       category text, subject text, summary text, extracted jsonb, resume_requested boolean, needs_action boolean,
       last_message_at timestamptz, suggested_application_status text, needs_user_input jsonb, urgency text, new_messages int)
LEFT JOIN companies co ON co.normalized_name = lower(regexp_replace(t.company, '[^a-zA-Z0-9]+', '', 'g'))
ON CONFLICT (gmail_thread_id) DO UPDATE SET
  message_count       = email_threads.message_count + EXCLUDED.message_count,
  company_id          = COALESCE(EXCLUDED.company_id, email_threads.company_id),
  application_id      = COALESCE(email_threads.application_id, EXCLUDED.application_id),
  recruiter_person_id = COALESCE(EXCLUDED.recruiter_person_id, email_threads.recruiter_person_id),
  gmail_last_message_id        = EXCLUDED.gmail_last_message_id,
  category                     = EXCLUDED.category,
  summary                      = EXCLUDED.summary,
  extracted                    = EXCLUDED.extracted,
  resume_requested             = email_threads.resume_requested OR EXCLUDED.resume_requested,
  needs_action                 = EXCLUDED.needs_action,
  last_message_at              = GREATEST(email_threads.last_message_at, EXCLUDED.last_message_at),
  suggested_application_status = COALESCE(EXCLUDED.suggested_application_status, email_threads.suggested_application_status),
  needs_user_input             = EXCLUDED.needs_user_input,
  urgency                      = EXCLUDED.urgency,
  updated_at                   = now();

INSERT INTO email_messages (gmail_message_id, gmail_thread_id, email_thread_id, received_at, from_email, from_name, subject,
                            relevance, triage_reason, status, attempts, last_error, category, body_excerpt, analysis, model,
                            input_tokens, output_tokens)
SELECT r.gmail_message_id, r.gmail_thread_id, (SELECT id FROM email_threads WHERE gmail_thread_id = r.gmail_thread_id),
       r.received_at, r.from_email, r.from_name, r.subject, r.relevance, r.triage_reason, r.status, 1, r.last_error,
       r.category, r.body_excerpt, r.analysis, r.model, r.input_tokens, r.output_tokens
FROM jsonb_to_recordset($1::jsonb) AS r(gmail_message_id text, gmail_thread_id text, received_at timestamptz, from_email text,
       from_name text, subject text, relevance text, triage_reason text, status text, last_error text, category text,
       body_excerpt text, analysis jsonb, model text, input_tokens int, output_tokens int)
ON CONFLICT (gmail_message_id) DO UPDATE SET
  status = EXCLUDED.status, attempts = email_messages.attempts + 1, last_error = EXCLUDED.last_error,
  relevance = EXCLUDED.relevance, category = EXCLUDED.category, body_excerpt = EXCLUDED.body_excerpt,
  analysis = EXCLUDED.analysis, model = EXCLUDED.model, input_tokens = EXCLUDED.input_tokens,
  output_tokens = EXCLUDED.output_tokens, processed_at = now(),
  email_thread_id = COALESCE(EXCLUDED.email_thread_id, email_messages.email_thread_id);

-- Give up after max attempts, and tell the user to look at the mail themselves.
INSERT INTO notifications (kind, priority, title, body, ref_table, ref_id, dedupe_key)
SELECT 'email_failed', 2, 'Could not process an email: ' || left(COALESCE(e.subject, '(no subject)'), 120),
       'From ' || COALESCE(e.from_email, '?') || '. Last error: ' || COALESCE(e.last_error, '?') || '. Please read it in Gmail.',
       'email_messages', e.id, 'email:' || e.gmail_message_id || ':failed'
FROM email_messages e
WHERE e.status = 'error' AND e.attempts >= $6::int
  AND e.gmail_message_id IN (SELECT x->>'gmail_message_id' FROM jsonb_array_elements($1::jsonb) x)
ON CONFLICT (dedupe_key) DO NOTHING;

INSERT INTO interviews (application_id, email_thread_id, round, scheduled_at, duration_minutes, meeting_link, source_text,
                        time_assumed_ist, mode, status)
SELECT t.application_id, t.id, i.round, i.scheduled_at, i.duration_minutes, i.meeting_link, i.source_text,
       COALESCE(i.time_assumed_ist, false), i.mode, 'detected'
FROM jsonb_to_recordset($4::jsonb) AS i(gmail_thread_id text, round text, scheduled_at timestamptz, duration_minutes int,
       meeting_link text, source_text text, time_assumed_ist boolean, mode text)
JOIN email_threads t ON t.gmail_thread_id = i.gmail_thread_id
ON CONFLICT (email_thread_id, scheduled_at) WHERE scheduled_at IS NOT NULL DO NOTHING;

-- A newer message in the thread replaces a draft that was not created in Gmail yet.
UPDATE email_drafts d SET status = 'superseded', updated_at = now()
FROM jsonb_to_recordset($5::jsonb) AS n(gmail_thread_id text)
WHERE d.gmail_thread_id = n.gmail_thread_id AND d.status = 'pending';

INSERT INTO email_drafts (email_thread_id, gmail_thread_id, reply_to_message_id, to_email, subject, body, raw, needs_user_input)
SELECT (SELECT id FROM email_threads WHERE gmail_thread_id = n.gmail_thread_id), n.gmail_thread_id, n.reply_to_message_id,
       n.to_email, n.subject, n.body, n.raw, ARRAY(SELECT jsonb_array_elements_text(n.needs_user_input))
FROM jsonb_to_recordset($5::jsonb) AS n(gmail_thread_id text, reply_to_message_id text, to_email text, subject text,
       body text, raw text, needs_user_input jsonb)
ON CONFLICT (reply_to_message_id) DO NOTHING;

INSERT INTO notifications (kind, priority, title, body, ref_table, ref_id, dedupe_key)
SELECT n.kind, n.priority, n.title, n.body, 'email_threads',
       (SELECT id FROM email_threads WHERE gmail_thread_id = n.gmail_thread_id), n.dedupe_key
FROM jsonb_to_recordset($7::jsonb) AS n(kind text, priority int, title text, body text, dedupe_key text, gmail_thread_id text)
ON CONFLICT (dedupe_key) DO NOTHING;

INSERT INTO execution_log (workflow, execution_id, node, level, message, context)
VALUES ('C1 Gmail Triage', $8, 'Save', CASE WHEN ($10::jsonb->>'failed')::int > 0 THEN 'warn' ELSE 'info' END, $9, $10::jsonb);

UPDATE workflow_locks SET locked_until = now() WHERE name = 'C1' AND holder = $8::text;

-- Claim drafts to create in Gmail (this run's and earlier retries). Stale claims are released first.
UPDATE email_drafts SET status = 'pending', updated_at = now()
WHERE status = 'creating' AND updated_at < now() - interval '30 minutes';
UPDATE email_drafts d SET status = 'creating', attempts = d.attempts + 1, updated_at = now()
WHERE d.status = 'pending' AND d.attempts < $6::int
RETURNING d.id, d.gmail_thread_id, d.raw;
