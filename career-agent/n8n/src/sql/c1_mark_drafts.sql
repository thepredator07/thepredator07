-- $1 = [{id, ok, gmail_draft_id, error}], $2 = max attempts
UPDATE email_drafts d SET
  status = CASE WHEN r.ok THEN 'created' WHEN d.attempts >= $2::int THEN 'failed' ELSE 'pending' END,
  gmail_draft_id = COALESCE(r.gmail_draft_id, d.gmail_draft_id), last_error = r.error, updated_at = now()
FROM jsonb_to_recordset($1::jsonb) AS r(id bigint, ok boolean, gmail_draft_id text, error text)
WHERE d.id = r.id;

UPDATE email_threads t SET draft_gmail_id = d.gmail_draft_id
FROM email_drafts d
WHERE d.status = 'created' AND t.id = d.email_thread_id
  AND d.id IN (SELECT (x->>'id')::bigint FROM jsonb_array_elements($1::jsonb) x WHERE (x->>'ok')::boolean);

-- Sending is your action in Gmail; this queue row records that a draft is waiting for you.
INSERT INTO approvals (kind, ref_table, ref_id, payload)
SELECT 'send_email', 'email_drafts', d.id,
       jsonb_build_object('gmail_draft_id', d.gmail_draft_id, 'to', d.to_email, 'subject', d.subject, 'needs_user_input', d.needs_user_input)
FROM email_drafts d
WHERE d.status = 'created' AND d.id IN (SELECT (x->>'id')::bigint FROM jsonb_array_elements($1::jsonb) x)
ON CONFLICT (kind, ref_table, ref_id) DO NOTHING;

INSERT INTO notifications (kind, priority, title, body, ref_table, ref_id, dedupe_key)
SELECT 'draft_failed', 2, 'Could not create a Gmail draft: ' || left(d.subject, 120), d.last_error, 'email_drafts', d.id, 'draft:' || d.id || ':failed'
FROM email_drafts d
WHERE d.status = 'failed' AND d.id IN (SELECT (x->>'id')::bigint FROM jsonb_array_elements($1::jsonb) x)
ON CONFLICT (dedupe_key) DO NOTHING;

SELECT count(*) FILTER (WHERE (x->>'ok')::boolean) AS drafts_created,
       count(*) FILTER (WHERE NOT (x->>'ok')::boolean) AS drafts_failed
FROM jsonb_array_elements($1::jsonb) x;
