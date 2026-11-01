-- $1 = Gmail messages.list "messages" array, $2 = max attempts, $3 = execution id.
-- New messages, plus failed ones still under the attempt limit. Releases the lease when there is nothing to do.
UPDATE workflow_locks SET locked_until = now()
WHERE name = 'C1' AND holder = $3::text
  AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements($1::jsonb) m
                  WHERE NOT EXISTS (SELECT 1 FROM email_messages e WHERE e.gmail_message_id = m->>'id'
                                    AND (e.status <> 'error' OR e.attempts >= $2::int)));
SELECT m->>'id' AS id, m->>'threadId' AS thread_id
FROM jsonb_array_elements($1::jsonb) m
WHERE NOT EXISTS (
  SELECT 1 FROM email_messages e
  WHERE e.gmail_message_id = m->>'id' AND (e.status <> 'error' OR e.attempts >= $2::int));
