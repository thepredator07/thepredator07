-- $1 = run id, $2 = per-source results (json array), $3 = execution id
UPDATE discovery_runs dr SET
  finished_at       = now(),
  source_results    = $2::jsonb,
  sources_ok        = (SELECT count(*) FROM jsonb_array_elements($2::jsonb) e WHERE (e->>'ok')::boolean),
  sources_failed    = (SELECT count(*) FROM jsonb_array_elements($2::jsonb) e WHERE NOT (e->>'ok')::boolean),
  jobs_fetched      = (SELECT COALESCE(sum((e->>'fetched')::int), 0) FROM jsonb_array_elements($2::jsonb) e),
  jobs_prefiltered  = (SELECT COALESCE(sum((e->>'passed')::int), 0) FROM jsonb_array_elements($2::jsonb) e),
  jobs_new          = (SELECT count(*) FROM jobs WHERE first_seen_run_id = dr.id),
  jobs_duplicates   = (SELECT count(*) FROM jobs WHERE first_seen_run_id = dr.id AND duplicate_of IS NOT NULL),
  jobs_expired      = (SELECT count(*) FROM jobs WHERE expired_at >= dr.started_at),
  jobs_scored       = (SELECT count(*) FROM job_scores WHERE created_at >= dr.started_at),
  jobs_score_failed = (SELECT count(*) FROM execution_log WHERE execution_id = $3 AND node = 'Claude: score job'),
  status = CASE
    WHEN jsonb_array_length($2::jsonb) > 0
     AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements($2::jsonb) e WHERE (e->>'ok')::boolean) THEN 'failed'
    WHEN EXISTS (SELECT 1 FROM jsonb_array_elements($2::jsonb) e WHERE NOT (e->>'ok')::boolean)
      OR EXISTS (SELECT 1 FROM execution_log WHERE execution_id = $3 AND node = 'Claude: score job') THEN 'partial'
    ELSE 'succeeded' END
WHERE dr.id = $1::int;

INSERT INTO execution_log (workflow, execution_id, node, level, message, context)
SELECT 'A1 Job Discovery', $3, 'Finish run', CASE WHEN status = 'succeeded' THEN 'info' ELSE 'warn' END,
       format('Discovery run %s %s: %s new, %s scored, %s expired, %s/%s sources failed',
              id, status, jobs_new, jobs_scored, jobs_expired, sources_failed, sources_ok + sources_failed),
       to_jsonb(dr) - 'source_results'
FROM discovery_runs dr WHERE id = $1::int;

SELECT dr.id AS run_id, dr.status, dr.jobs_fetched, dr.jobs_prefiltered, dr.jobs_new, dr.jobs_duplicates,
       dr.jobs_scored, dr.jobs_score_failed, dr.jobs_expired, dr.sources_ok, dr.sources_failed,
       COALESCE((SELECT json_agg(t) FROM (
          SELECT m.company, m.title, m.matched_location, m.score, m.verdict, m.comp_fit, m.job_url
          FROM v_job_matches m JOIN jobs j ON j.id = m.id
          WHERE j.first_seen_run_id = dr.id AND m.score IS NOT NULL
          ORDER BY m.score DESC LIMIT 5) t), '[]'::json) AS top_new_matches
FROM discovery_runs dr WHERE dr.id = $1::int;
