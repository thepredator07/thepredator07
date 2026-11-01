-- Claims up to 20 unsent notifications at or above the immediate priority, if Telegram is configured.
UPDATE notifications n SET attempts = n.attempts + 1
FROM (SELECT value->>'telegram_chat_id' AS chat_id, COALESCE((value->>'immediate_max_priority')::int, 2) AS max_priority
      FROM settings WHERE key = 'notifications') s
WHERE s.chat_id IS NOT NULL AND s.chat_id <> ''
  AND n.id IN (SELECT id FROM notifications
               WHERE sent_at IS NULL AND attempts < 5 AND created_at > now() - interval '3 days'
                 AND priority <= (SELECT COALESCE((value->>'immediate_max_priority')::int, 2) FROM settings WHERE key = 'notifications')
               ORDER BY priority, created_at LIMIT 20 FOR UPDATE SKIP LOCKED)
RETURNING n.id, n.priority, n.title, n.body, s.chat_id;
