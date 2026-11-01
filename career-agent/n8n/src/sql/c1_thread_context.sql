-- $1 = thread ids (json array). What we already know about these threads, for Claude.
SELECT t.gmail_thread_id, t.category, t.summary, c.name AS company, a.role, a.status AS application_status
FROM email_threads t
LEFT JOIN companies c ON c.id = t.company_id
LEFT JOIN applications a ON a.id = t.application_id
WHERE t.gmail_thread_id IN (SELECT jsonb_array_elements_text($1::jsonb));
