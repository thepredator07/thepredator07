-- $1 = n8n execution id (lease holder)
INSERT INTO workflow_locks (name, holder, locked_until)
VALUES ('C1', $1, now() + make_interval(mins => COALESCE((SELECT (value->>'lock_minutes')::int FROM settings WHERE key = 'gmail'), 14)))
ON CONFLICT (name) DO UPDATE SET holder = EXCLUDED.holder, locked_until = EXCLUDED.locked_until
WHERE workflow_locks.locked_until < now();
SELECT (SELECT holder FROM workflow_locks WHERE name = 'C1') = $1::text           AS got_lock,
       (SELECT value FROM settings WHERE key = 'gmail')                           AS cfg,
       (SELECT value FROM settings WHERE key = 'candidate_profile')               AS profile,
       (SELECT value->>'anthropic_base_url' FROM settings WHERE key = 'discovery') AS anthropic_base_url,
       COALESCE((SELECT json_agg(gmail_thread_id) FROM email_threads
                 WHERE last_message_at > now() - interval '90 days'), '[]'::json) AS tracked_threads;
