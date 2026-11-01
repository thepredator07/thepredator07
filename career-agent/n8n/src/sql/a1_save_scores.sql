-- $1 = scores (json array), $2 = job updates from verified salary quotes, $3 = execution id, $4 = failures
INSERT INTO job_scores (job_id, prompt_version, model, total, technical, experience, location, company, compensation,
                        verdict, company_type, matched_skills, missing_skills, red_flags, summary, raw_response,
                        input_tokens, output_tokens, cache_read_tokens)
SELECT x.job_id, x.prompt_version, x.model, x.total, x.technical, x.experience, x.location, x.company, x.compensation,
       x.verdict, x.company_type,
       ARRAY(SELECT jsonb_array_elements_text(x.matched_skills)),
       ARRAY(SELECT jsonb_array_elements_text(x.missing_skills)),
       ARRAY(SELECT jsonb_array_elements_text(x.red_flags)),
       x.summary, x.raw_response, x.input_tokens, x.output_tokens, x.cache_read_tokens
FROM jsonb_to_recordset($1::jsonb) AS x(
       job_id bigint, prompt_version text, model text, total int, technical int, experience int, location int,
       company int, compensation int, verdict text, company_type text, matched_skills jsonb, missing_skills jsonb,
       red_flags jsonb, summary text, raw_response jsonb, input_tokens int, output_tokens int, cache_read_tokens int)
ON CONFLICT (job_id, prompt_version) DO NOTHING;

UPDATE jobs j SET
  salary_text    = u.salary_text,
  salary_source  = u.salary_source,
  salary_min_inr = COALESCE(u.salary_min_inr, j.salary_min_inr),
  salary_max_inr = COALESCE(u.salary_max_inr, j.salary_max_inr),
  comp_fit       = COALESCE(u.comp_fit, j.comp_fit)
FROM jsonb_to_recordset($2::jsonb) AS u(job_id bigint, salary_text text, salary_source text,
                                        salary_min_inr bigint, salary_max_inr bigint, comp_fit text)
WHERE j.id = u.job_id AND j.salary_source = 'none';

INSERT INTO execution_log (workflow, execution_id, node, level, message, context)
SELECT 'A1 Job Discovery', $3, 'Claude: score job', 'warn', 'Scoring failed: ' || f.error, to_jsonb(f)
FROM jsonb_to_recordset($4::jsonb) AS f(job_id bigint, error text);

SELECT count(*) AS saved FROM jsonb_array_elements($1::jsonb);
