-- $1 = skipped / job-alert messages (no body is stored for these), $2 = number of candidates, $3 = execution id
INSERT INTO email_messages (gmail_message_id, gmail_thread_id, received_at, from_email, from_name, subject, relevance, triage_reason, status)
SELECT s.gmail_message_id, s.gmail_thread_id, s.received_at, s.from_email, s.from_name, s.subject, s.relevance, s.triage_reason, 'skipped'
FROM jsonb_to_recordset($1::jsonb) AS s(gmail_message_id text, gmail_thread_id text, received_at timestamptz, from_email text,
                                         from_name text, subject text, relevance text, triage_reason text)
ON CONFLICT (gmail_message_id) DO NOTHING;
UPDATE workflow_locks SET locked_until = now() WHERE name = 'C1' AND holder = $3::text AND $2::int = 0;
SELECT jsonb_array_length($1::jsonb) AS skipped;
