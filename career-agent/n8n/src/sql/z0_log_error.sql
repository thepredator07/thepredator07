-- $1 workflow name, $2 execution id, $3 last node, $4 error message, $5 execution json
INSERT INTO execution_log (workflow, execution_id, node, level, message, context)
VALUES ($1, $2, $3, 'error', $4, $5::jsonb);
UPDATE discovery_runs SET status = 'failed', finished_at = now(), error = $4
 WHERE n8n_execution_id = $2 AND status = 'running';
SELECT (SELECT value->>'telegram_chat_id' FROM settings WHERE key = 'notifications') AS telegram_chat_id;
