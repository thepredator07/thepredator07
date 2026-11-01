# Module A: Automated Job Discovery

Workflow **A1 Job Discovery** (`n8n/workflows/A1_job_discovery.json`, id `careerA1Discover`) and
the shared **Z0 Error Handler** (`careerZ0ErrorHnd`).

## 1. Summary

| | |
|---|---|
| **Trigger** | Schedule, 07:00 Asia/Kolkata daily; plus a manual trigger |
| **Input** | `settings` (`discovery`, `candidate_profile`), active rows of `job_sources` |
| **Processing** | Fetch each board once → normalise 5 ATS formats → pre-filter → upsert → expire → de-dupe → Claude scores new survivors one at a time |
| **Claude** | One Messages API call per new job, structured JSON output, `effort: low` |
| **Output** | Rows in `jobs`, `job_scores`, `discovery_runs`, `execution_log`; run summary with the top 5 new matches |
| **External actions** | None. Read-only GET/POST to public job-board APIs; nothing is applied for |
| **Approval** | Not needed for this workflow. Shortlisting a job (`jobs.status = 'shortlisted'`) is your decision and starts Module F later |

## 2. Nodes

| # | Node | Type | What it does |
|---|---|---|---|
| 1 | Every day 07:00 IST / Run manually | Schedule / Manual | Start |
| 2 | Start run | Postgres | Marks runs stuck in `running` > 6 h as failed; inserts a `discovery_runs` row |
| 3 | Load config | Postgres | One row: discovery settings, candidate profile, active sources |
| 4 | Build requests | Code | One request per source (URL, method, body). Throws if no sources are active |
| 5 | Fetch job board | HTTP Request | 30 s timeout, 1.5 s between boards, `neverError` + full response so a 404 becomes data, network errors continue as error items |
| 6 | Normalize & filter | Code | Maps Greenhouse / Lever / Ashby / SmartRecruiters / Workday payloads to one job shape; extracts experience and salary; location match; pre-filter; fingerprint |
| 7 | Save jobs, expire, dedupe | Postgres (one implicit transaction) | Upsert jobs; update source health; expire missing jobs; mark cross-board duplicates |
| 8 | Select jobs to score | Postgres | Active, pre-filter-passed, non-duplicate, `status='discovered'`, not yet scored with this prompt version; newest first; capped |
| 9 | Anything to score? | IF | Skips straight to Finish when nothing is new |
| 10 | Build Claude requests | Code | Request body per job |
| 11 | One job at a time | Loop Over Items (batch 1) | Makes n8n's retry apply per job (n8n retries whole nodes and checks only the first item) |
| 12 | Claude: score job | HTTP Request | `POST /v1/messages`, header-auth credential, 180 s timeout, 3 tries 5 s apart (429 / 5xx / 529), continues on final failure |
| 13 | Attach job | Code | Pairs the response with its job |
| 14 | Parse scores | Code | Validates, clamps, computes compensation and total |
| 15 | Save scores | Postgres | Inserts scores, applies verified salary quotes, logs failures |
| 16 | Finish run | Postgres | Final counts and status (`succeeded` / `partial` / `failed`), log row, top 5 new matches |

The JavaScript in the Code nodes and the SQL are generated from `n8n/src/` by
`scripts/build-workflows.mjs`, so what runs in n8n is exactly what the unit tests cover. Change
`n8n/src`, run `node scripts/build-workflows.mjs`, re-import.

## 3. Sources

| ATS | Endpoint | Notes |
|---|---|---|
| Greenhouse | `GET boards-api.greenhouse.io/v1/boards/{token}/jobs?content=true` | Full list with descriptions |
| Lever | `GET api.lever.co/v0/postings/{site}?mode=json` | Structured salary when published. EU-hosted boards: set `endpoint_url` to `api.eu.lever.co` |
| Ashby | `GET api.ashbyhq.com/posting-api/job-board/{org}?includeCompensation=true` | Structured salary when published |
| SmartRecruiters | `GET api.smartrecruiters.com/v1/companies/{id}/postings?country=in&limit=100` | List has no descriptions: scored on title/location, capped at 28/40 technical and at most `good_match` |
| Workday | `POST {host}/wday/cxs/{tenant}/{site}/jobs` | The public career site's own JSON endpoint, not a documented API. **Off by default**; enable per company after checking that site's terms. First page only (`workday_page_size`, server-side `search_text`), so absence is not treated as expiry for 14 days |

The seeded board tokens in `db/002_seed.sql` are best guesses and **not verified** (this build
environment could not reach the job boards). Run `scripts/verify-sources.sh` once; the workflow
also marks a source `verified` on its first success and deactivates it after
`max_source_failures` (default 5) consecutive failures.

Adding a company:

```sql
INSERT INTO companies (name, category, priority) VALUES ('Acme', 'product', 2);
INSERT INTO job_sources (company_id, ats, board_token)
VALUES ((SELECT id FROM companies WHERE name = 'Acme'), 'greenhouse', 'acme');
-- Workday: board_token = 'acme.wd3.myworkdayjobs.com|acme|AcmeCareers', search_text = 'java'
```

Find the token in the careers page URL: `boards.greenhouse.io/<token>`, `jobs.lever.co/<site>`,
`jobs.ashbyhq.com/<org>`, `jobs.smartrecruiters.com/<CompanyId>`,
`<tenant>.wd<N>.myworkdayjobs.com/<site>`.

## 4. Filtering, de-duplication and expiry

Pre-filter (deterministic, in `discovery-lib.js`), in order; the first failing rule is stored in
`jobs.prefilter_reason`:

1. Title must match `title_include_regex` (backend, Java, Spring, SDE, software engineer/developer, platform, distributed, MTS, …).
2. Title must not match `title_exclude_regex` (intern, staff, principal, manager, frontend, mobile, QA/SDET, data science, …).
3. Location: Mumbai/Bombay/Navi Mumbai/Thane, Bengaluru/Bangalore, Hyderabad/Secunderabad, Pune, or remote that is not restricted to another country. Other Indian cities → `india_other_city`. Empty location passes and Claude judges it.
4. Posted within `max_job_age_days` (30).
5. Experience: parsed from the JD ("3-5 years", "4+ yrs", "minimum 2 years … experience"). Minimum ≥ 6 → `experience_over`; maximum < 2 → `experience_under`. `exp_in_2_5` flags requirements overlapping 2–5 years.

Jobs that fail are still stored (without description) so they are not re-processed, and so you
can see why something was dropped. All regexes and lists live in `settings.discovery`; edit with:

```sql
UPDATE settings SET value = jsonb_set(value, '{max_job_age_days}', '21') WHERE key = 'discovery';
```

- **Same posting seen again**: `UNIQUE(source_id, external_id)` → only `last_seen_at` and mutable fields update.
- **Same job on two boards** (e.g. global and India boards): `fingerprint = company|normalised title|city`; the later one gets `duplicate_of` and is not scored.
- **Expired**: a job absent from a complete listing of a source that answered successfully is set `is_active = false, expired_at = now()`. A failed fetch never expires anything.

## 5. Scoring

Total 100 = technical 40 + experience 20 + location 15 + company 15 + compensation 10.

- Claude scores the first four with the rubric below and returns matched/missing skills, red
  flags, verdict and a one-line summary as **structured output** (`output_config.format`
  JSON schema), so the response always parses.
- Code clamps every component to its range and computes the total itself.
- **Compensation is computed in code**: INR yearly range overlapping 15–20 LPA or above it → 10;
  below with max ≥ 12 LPA → 4; lower → 0; unknown or non-INR → 5 (neutral). Sources: ATS
  structured salary, or a salary pattern written in the JD (`₹18-26 LPA`, `15 to 22 lakhs`,
  `₹16,00,000 – ₹24,00,000`). Claude's `salary_quote` is used only if the exact text is in the JD.
  Experience ranges like "15-20 engineers" or "3-5 years" are never read as salary.
- Request: model from `settings.discovery.scoring_model` (default `claude-opus-5-5`),
  `effort: low` (enough for a rubric task; raise to `medium` if scores look shallow), no
  `temperature` (rejected by the current API version), `anthropic-version: 2026-10-21`.
- **Refusal fallback** is enabled (`fallbacks: "default"` with beta header
  `server-side-fallback-2026-07-01`): if a safety classifier ever declines a request, the API
  re-runs it on a fallback model in the same call. Set `use_fallbacks: false` in settings to turn it
  off. A final refusal is logged and the job is retried next run.
- The system prompt carries `cache_control`. At ~1,300 tokens it may be below the model's minimum
  cacheable prefix, in which case nothing is cached (harmless); `job_scores.cache_read_tokens`
  shows whether it is.

### Prompt (score-v1)

Full text: `buildSystemPrompt()` / `buildUserMessage()` in `n8n/src/discovery-lib.js`. Structure:

```
System:
  Role + why calibration matters
  <candidate_profile> JSON from settings.candidate_profile </candidate_profile>
  Rubric with anchors for technical (0-40), experience (0-20), location (0-15), company (0-15)
  Hard rules: no invented facts; salary_quote and experience_required verbatim or null;
  missing description -> technical max 28, not strong_match; short concrete red flags.
User:
  <job_fields> company, category, title, location, remote, regex-extracted experience,
               structured salary </job_fields>
  <job_description> plain text (truncated="true" if over max_description_chars) </job_description>
```

Changing the rubric: bump `prompt_version` in settings (e.g. `score-v2`). Scores are unique per
`(job_id, prompt_version)`, so active jobs get re-scored with the new prompt on following runs
(up to the per-run cap) and old scores are kept for comparison.

## 6. Error handling

| Failure | Behaviour |
|---|---|
| Job board 404 / 5xx / timeout / bad JSON | Recorded per source in `discovery_runs.source_results`; `consecutive_failures + 1`; auto-disabled at the limit; other boards unaffected; run status `partial`. Retried next day |
| Claude 429 / 5xx / 529 overloaded | Retried up to 3 times, 5 s apart, per job |
| Claude still failing, refusal, `max_tokens`, unparsable output | Logged to `execution_log` (node `Claude: score job`); job stays unscored and is picked up next run |
| Postgres error | Node retried 3 times; then the execution fails → Z0 |
| Anything that stops the execution | Z0 Error Handler logs to `execution_log`, marks the run `failed`, and (if enabled) sends you a Telegram alert |
| Run never finished (crash, restart) | Next run marks it `failed` after 6 h |

To use Telegram alerts: create a bot with @BotFather, put `TELEGRAM_BOT_TOKEN` and
`TELEGRAM_CHAT_ID` in `.env`, re-run `scripts/bootstrap.sh`, open **Z0 Error Handler** and enable
the *Telegram alert* node.

## 7. Testing

**Unit tests** (no dependencies): `node --test tests/discovery-lib.test.mjs` — 11 tests over
fixtures for all five ATS formats: filters, location rules, experience and salary extraction
("3-5 years" never becomes salary), Claude request shape, response parsing (clamping, refusals,
unverified salary quotes ignored, title-only cap).

**End-to-end** in a real n8n 2.45.7 against Postgres, with a mock server for job boards and the
Claude API (no network, no spend): `tests/e2e/run-e2e.sh` (see the header for env vars; CI runs it
on every change). It runs A1 twice and checks 24 assertions, including: 6 sources OK + 404 + 500
handled → `partial`; 15 jobs fetched, 7 pass the pre-filter; a cross-board duplicate detected and
not scored; a 529 overload retried and scored; a refusal logged and retried next run; totals equal
component sums; JD salary → 10 points, below-target salary → 4, unknown stays unknown (5);
filtered jobs keep no description; second run: removed job expired, Workday job (partial listing)
kept, nothing re-scored, failing source auto-disabled. Result at the time of writing: **all pass**.

**First live run** (after deployment):

1. `scripts/verify-sources.sh --apply` — fix or drop wrong tokens.
2. Temporarily lower the cap: `UPDATE settings SET value = jsonb_set(value,'{max_jobs_scored_per_run}','10') WHERE key='discovery';`
3. Open A1 in n8n → *Execute workflow*. Check the *Finish run* output.
4. Inspect: `SELECT * FROM v_job_matches ORDER BY score DESC NULLS LAST LIMIT 20;` — read 10 scored
   jobs and their postings. If the scores look wrong, adjust the rubric or effort before scaling.
5. Check why things were dropped: `SELECT prefilter_reason, count(*) FROM jobs GROUP BY 1 ORDER BY 2 DESC;`
6. Restore the cap (60), publish A1.

To compare a cheaper model on your real data, score the same 20 jobs under a second prompt
version with another model and compare totals side by side before switching:

```sql
UPDATE settings SET value = value || '{"scoring_model":"claude-sonnet-5-5","prompt_version":"score-v1-sonnet"}' WHERE key='discovery';
-- run A1 once, then:
SELECT a.job_id, a.total AS opus, b.total AS sonnet, a.verdict, b.verdict
FROM job_scores a JOIN job_scores b USING (job_id)
WHERE a.prompt_version = 'score-v1' AND b.prompt_version = 'score-v1-sonnet';
```

## 8. Deployment

On a VM (Ubuntu 24.04, 2 vCPU / 4 GB is plenty) or your own machine with Docker:

```bash
git clone <this repo> && cd thepredator07/career-agent
cp .env.example .env && nano .env        # passwords (no single quotes), openssl rand -hex 32 for N8N_ENCRYPTION_KEY, API key
docker compose up -d                      # first boot creates the career DB, schema, seed, career_app role
scripts/bootstrap.sh                      # credentials + workflows (IDs fixed, so they bind automatically)
ssh -L 5678:localhost:5678 you@vm         # from your laptop, then open http://localhost:5678 and create the owner account
scripts/verify-sources.sh --apply
# run A1 manually once (section 7), then:
scripts/bootstrap.sh --publish            # publishes Z0 and A1; the 07:00 IST schedule is now live
```

- **Anthropic key**: create it in the Claude Console, set a monthly spend limit on the workspace.
- **Backups**: `docker compose exec postgres pg_dump -U $POSTGRES_ADMIN_USER career | gzip > career-$(date +%F).sql.gz` daily via cron; keep `N8N_ENCRYPTION_KEY` safe (credentials can't be decrypted without it).
- **Upgrading n8n**: change the pinned image tag, run the e2e test against the new version first.
- Module A needs no public URL. Telegram approval buttons (later modules) will need HTTPS:
  Cloudflare Tunnel or Caddy in front of n8n, plus `N8N_WEBHOOK_URL`.

## 9. Useful queries

```sql
-- Today's best new jobs
SELECT company, title, matched_location, score, verdict, comp_fit, summary, job_url
FROM v_job_matches WHERE first_seen_at > now() - interval '1 day' AND score >= 70 ORDER BY score DESC;

-- Shortlist a job (you decide; Module F starts from here)
UPDATE jobs SET status = 'shortlisted' WHERE id = 123;

-- Source health
SELECT c.name, s.ats, s.is_active, s.verified, s.consecutive_failures, s.last_status, s.last_success_at
FROM job_sources s JOIN companies c ON c.id = s.company_id ORDER BY s.is_active, c.name;

-- Run history
SELECT id, started_at, status, jobs_fetched, jobs_prefiltered, jobs_new, jobs_scored, jobs_expired, sources_failed
FROM discovery_runs ORDER BY id DESC LIMIT 14;

-- Actual Claude spend for scoring, last 30 days (Opus 5.5 prices; edit for other models)
SELECT count(*) AS jobs, sum(input_tokens) AS input_tokens, sum(output_tokens) AS output_tokens,
       round((sum(input_tokens) * 4 + sum(output_tokens) * 20) / 1e6 * 88, 0) AS approx_inr
FROM job_scores WHERE created_at > now() - interval '30 days';
```
