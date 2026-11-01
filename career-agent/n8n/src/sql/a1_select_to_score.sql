-- $1 = prompt version, $2 = max jobs to score this run.
-- Picks up anything not yet scored, so a failed Claude call is retried on the next run.
SELECT j.id, j.title, j.location_raw, j.matched_location, j.is_remote, j.department, j.employment_type,
       j.description_text, j.exp_text, j.exp_fit, j.salary_source, j.salary_text, j.salary_currency,
       j.salary_min_inr, j.salary_max_inr, j.comp_fit, j.posted_at,
       c.name AS company, c.category AS company_category
FROM jobs j JOIN companies c ON c.id = j.company_id
WHERE j.is_active AND j.prefilter_pass AND j.duplicate_of IS NULL AND j.status = 'discovered'
  AND NOT EXISTS (SELECT 1 FROM job_scores s WHERE s.job_id = j.id AND s.prompt_version = $1)
ORDER BY j.first_seen_at DESC, j.id DESC
LIMIT $2::int;
