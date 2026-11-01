-- $1 = jobs (json array), $2 = run id, $3 = per-source results (json array), $4 = max consecutive failures
INSERT INTO jobs (source_id, company_id, external_id, fingerprint, title, location_raw, matched_location, is_remote,
                  department, employment_type, description_text, exp_min, exp_max, exp_text, exp_fit, exp_in_2_5,
                  salary_min_inr, salary_max_inr, salary_currency, salary_interval, salary_source, salary_text, comp_fit,
                  posted_at, job_url, apply_url, prefilter_pass, prefilter_reason, first_seen_run_id)
SELECT x.source_id, x.company_id, x.external_id, x.fingerprint, x.title, x.location_raw, x.matched_location, x.is_remote,
       x.department, x.employment_type, x.description_text, x.exp_min, x.exp_max, x.exp_text, x.exp_fit, x.exp_in_2_5,
       x.salary_min_inr, x.salary_max_inr, x.salary_currency, x.salary_interval, x.salary_source, x.salary_text, x.comp_fit,
       x.posted_at, x.job_url, x.apply_url, x.prefilter_pass, x.prefilter_reason, $2::int
FROM jsonb_to_recordset($1::jsonb) AS x(
       source_id int, company_id int, external_id text, fingerprint text, title text, location_raw text,
       matched_location text, is_remote boolean, department text, employment_type text, description_text text,
       exp_min smallint, exp_max smallint, exp_text text, exp_fit text, exp_in_2_5 boolean,
       salary_min_inr bigint, salary_max_inr bigint, salary_currency text, salary_interval text, salary_source text,
       salary_text text, comp_fit text, posted_at timestamptz, job_url text, apply_url text,
       prefilter_pass boolean, prefilter_reason text)
ON CONFLICT (source_id, external_id) DO UPDATE SET
  last_seen_at     = now(),
  is_active        = true,
  expired_at       = NULL,
  apply_url        = EXCLUDED.apply_url,
  location_raw     = EXCLUDED.location_raw,
  matched_location = EXCLUDED.matched_location,
  prefilter_pass   = EXCLUDED.prefilter_pass,
  prefilter_reason = EXCLUDED.prefilter_reason,
  description_text = COALESCE(EXCLUDED.description_text, jobs.description_text);

-- Source health: verify on first success, count failures, auto-disable after N consecutive failures.
UPDATE job_sources s SET
  last_status          = r.status,
  last_error           = r.error,
  verified             = s.verified OR r.ok,
  last_success_at      = CASE WHEN r.ok THEN now() ELSE s.last_success_at END,
  consecutive_failures = CASE WHEN r.ok THEN 0 ELSE s.consecutive_failures + 1 END,
  is_active            = CASE WHEN NOT r.ok AND s.consecutive_failures + 1 >= $4::int THEN false ELSE s.is_active END
FROM jsonb_to_recordset($3::jsonb) AS r(source_id int, ok boolean, status int, error text)
WHERE s.id = r.source_id;

-- Expiry: a job missing from a complete listing of a healthy source is gone. For sources that only
-- return one page (Workday, large SmartRecruiters boards) require 14 days of absence instead.
UPDATE jobs j SET is_active = false, expired_at = now()
FROM discovery_runs dr, jsonb_to_recordset($3::jsonb) AS r(source_id int, ok boolean, complete boolean)
WHERE dr.id = $2::int AND r.ok AND j.source_id = r.source_id AND j.is_active
  AND j.last_seen_at < CASE WHEN r.complete THEN dr.started_at ELSE dr.started_at - interval '14 days' END;

-- Cross-source de-duplication: same company + normalised title + city already known -> duplicate.
UPDATE jobs j SET duplicate_of = d.id
FROM jobs d
WHERE j.first_seen_run_id = $2::int AND j.duplicate_of IS NULL
  AND d.fingerprint = j.fingerprint AND d.id < j.id AND d.duplicate_of IS NULL;

SELECT count(*) FILTER (WHERE first_seen_run_id = $2::int)                               AS jobs_new,
       count(*) FILTER (WHERE first_seen_run_id = $2::int AND duplicate_of IS NOT NULL)  AS jobs_duplicates
FROM jobs;
