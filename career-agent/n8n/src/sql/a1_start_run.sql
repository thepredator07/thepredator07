-- $1 = n8n execution id
UPDATE discovery_runs SET status = 'failed', finished_at = now(), error = 'abandoned: no finish recorded'
 WHERE status = 'running' AND started_at < now() - interval '6 hours';
INSERT INTO discovery_runs (n8n_execution_id) VALUES ($1) RETURNING id AS run_id, started_at;
