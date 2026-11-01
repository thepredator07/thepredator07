#!/usr/bin/env bash
# End-to-end test of A1 Job Discovery in a real n8n, against a local PostgreSQL and a mock
# job-board + mock Claude server. Costs nothing and touches no external service.
#
# Requirements: node 20+, psql, a PostgreSQL you can create databases on, and an n8n binary.
#   N8N_BIN=/path/to/node_modules/.bin/n8n PGHOST=127.0.0.1 PGPORT=5432 PGUSER=postgres PGPASSWORD=... \
#     tests/e2e/run-e2e.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
N8N_BIN="${N8N_BIN:-n8n}"
MOCK_PORT="${MOCK_PORT:-8787}"
DB="${E2E_DB:-career_e2e}"
WORK="$(mktemp -d)"
export N8N_USER_FOLDER="$WORK/n8n"
export N8N_ENCRYPTION_KEY="e2e-test-key-not-secret"
export N8N_DIAGNOSTICS_ENABLED=false N8N_RUNNERS_ENABLED=true GENERIC_TIMEZONE=Asia/Kolkata
MOCK="http://127.0.0.1:$MOCK_PORT"
FAILS=0

cleanup() { [[ -n "${MOCK_PID:-}" ]] && kill "$MOCK_PID" 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT

q() { psql -X -q -t -A -d "$DB" -c "$1"; }
check() { # check "<description>" "<sql returning one value>" "<expected>"
  local got; got="$(q "$2")"
  if [[ "$got" == "$3" ]]; then echo "  ok   $1"; else echo "  FAIL $1: expected '$3', got '$got'"; FAILS=$((FAILS+1)); fi
}

echo "== mock server"
node "$HERE/mock-server.mjs" "$MOCK_PORT" & MOCK_PID=$!
sleep 1

echo "== database $DB"
psql -X -q -d postgres -c "DROP DATABASE IF EXISTS $DB" -c "CREATE DATABASE $DB"
psql -X -q -v ON_ERROR_STOP=1 -d "$DB" -f "$ROOT/db/001_schema.sql" -f "$ROOT/db/002_seed.sql"
psql -X -q -v ON_ERROR_STOP=1 -d "$DB" <<SQL
DELETE FROM job_sources;
INSERT INTO companies (name, category) VALUES ('AcmePay','fintech'), ('CreditCo','fintech'), ('StreamCo','saas_cloud'),
  ('AcmeWD','product'), ('Ghost Inc','unknown'), ('Flaky Inc','unknown') ON CONFLICT DO NOTHING;
INSERT INTO job_sources (company_id, ats, board_token, endpoint_url, search_text, is_active) VALUES
 ((SELECT id FROM companies WHERE name='AcmePay'),   'greenhouse',      'acmepay',       '$MOCK/gh/acmepay', NULL, true),
 ((SELECT id FROM companies WHERE name='AcmePay'),   'greenhouse',      'acmepay-india', '$MOCK/gh/acmepay-india', NULL, true),
 ((SELECT id FROM companies WHERE name='CreditCo'),  'lever',           'creditco',      '$MOCK/lever/creditco', NULL, true),
 ((SELECT id FROM companies WHERE name='StreamCo'),  'ashby',           'streamco',      '$MOCK/ashby/streamco', NULL, true),
 ((SELECT id FROM companies WHERE name='Freshworks'),'smartrecruiters', 'Freshworks',    '$MOCK/sr/freshworks', NULL, true),
 ((SELECT id FROM companies WHERE name='AcmeWD'),    'workday',         'acme.wd1.myworkdayjobs.com|acme|Careers', '$MOCK/wd/acme', 'java', true),
 ((SELECT id FROM companies WHERE name='Ghost Inc'), 'greenhouse',      'missing',       '$MOCK/gh/missing', NULL, true),
 ((SELECT id FROM companies WHERE name='Flaky Inc'), 'greenhouse',      'broken',        '$MOCK/gh/broken', NULL, true);
UPDATE settings SET value = value || '{"anthropic_base_url": "$MOCK", "max_source_failures": 2}'::jsonb WHERE key = 'discovery';
SQL

echo "== n8n credentials + workflows"
cat > "$WORK/creds.json" <<JSON
[
 {"id":"careerPostgres01","name":"Career Postgres","type":"postgres",
  "data":{"host":"${PGHOST:-127.0.0.1}","port":${PGPORT:-5432},"database":"$DB","user":"${PGUSER:-postgres}","password":"${PGPASSWORD:-}","ssl":"disable","allowUnauthorizedCerts":false}},
 {"id":"anthropicApiKey1","name":"Anthropic API key","type":"httpHeaderAuth","data":{"name":"x-api-key","value":"test-key"}}
]
JSON
"$N8N_BIN" import:credentials --input="$WORK/creds.json" >>"$WORK/import.log" 2>&1 || { tail -30 "$WORK/import.log"; exit 1; }
"$N8N_BIN" import:workflow --input="$ROOT/n8n/workflows/Z0_error_handler.json" >>"$WORK/import.log" 2>&1 || { tail -30 "$WORK/import.log"; exit 1; }
"$N8N_BIN" import:workflow --input="$ROOT/n8n/workflows/A1_job_discovery.json" >>"$WORK/import.log" 2>&1 || { tail -30 "$WORK/import.log"; exit 1; }

run_a1() { "$N8N_BIN" execute --id careerA1Discover > "$WORK/run$1.log" 2>&1 || { echo "n8n execute failed:"; tail -50 "$WORK/run$1.log"; exit 1; }; }

echo "== run 1"
run_a1 1
check "run 1 finished as partial (2 broken sources + 1 refusal)" "SELECT status FROM discovery_runs WHERE id=1" "partial"
check "sources ok / failed" "SELECT sources_ok||'/'||sources_failed FROM discovery_runs WHERE id=1" "6/2"
check "jobs fetched" "SELECT jobs_fetched FROM discovery_runs WHERE id=1" "15"
check "jobs passing pre-filter" "SELECT count(*) FROM jobs WHERE prefilter_pass" "7"
check "cross-source duplicate detected" "SELECT count(*) FROM jobs WHERE duplicate_of IS NOT NULL" "1"
check "scored jobs (7 passing - 1 duplicate - 1 refusal)" "SELECT count(*) FROM job_scores" "5"
check "overloaded call retried and scored" "SELECT count(*) FROM job_scores s JOIN jobs j ON j.id=s.job_id WHERE j.title LIKE 'SDE 2%'" "1"
check "refusal logged, job left unscored" "SELECT count(*) FROM execution_log WHERE node='Claude: score job' AND message LIKE '%refusal%'" "1"
check "score total = component sum" "SELECT bool_and(total = technical+experience+location+company+compensation) FROM job_scores" "t"
check "JD salary -> comp 10 for GH 1001" "SELECT compensation||':'||j.comp_fit FROM job_scores s JOIN jobs j ON j.id=s.job_id WHERE j.external_id='1001'" "10:fit"
check "below-target structured salary -> comp 4" "SELECT s.compensation FROM job_scores s JOIN jobs j ON j.id=s.job_id WHERE j.external_id='as-1'" "4"
check "unknown salary stays unknown (never invented)" "SELECT j.comp_fit||':'||j.salary_source||':'||s.compensation FROM job_scores s JOIN jobs j ON j.id=s.job_id WHERE j.external_id='lv-2'" "unknown:none:5"
check "filtered-out jobs keep no description" "SELECT count(*) FROM jobs WHERE NOT prefilter_pass AND description_text IS NOT NULL" "0"
check "good sources verified" "SELECT count(*) FROM job_sources WHERE verified" "6"
check "404 source counted a failure" "SELECT consecutive_failures||':'||last_status FROM job_sources WHERE board_token='missing'" "1:404"
check "finish row logged" "SELECT count(*) FROM execution_log WHERE node='Finish run'" "1"

echo "== run 2 (job 1001 removed from the board)"
curl -s -X POST "$MOCK/control/remove-1001" >/dev/null
run_a1 2
check "run 2 fetched one fewer job" "SELECT jobs_fetched FROM discovery_runs WHERE id=2" "14"
check "no new jobs in run 2" "SELECT jobs_new FROM discovery_runs WHERE id=2" "0"
check "removed job expired" "SELECT is_active FROM jobs WHERE external_id='1001'" "f"
check "workday job not expired (partial listing)" "SELECT is_active FROM jobs WHERE external_id='R0001'" "t"
check "already-scored jobs not re-scored" "SELECT count(*) FROM job_scores" "5"
check "refused job retried in run 2" "SELECT count(*) FROM execution_log WHERE node='Claude: score job'" "2"
check "failing source auto-disabled after 2 failures" "SELECT is_active::text||':'||consecutive_failures FROM job_sources WHERE board_token='missing'" "false:2"
check "no duplicate job rows" "SELECT count(*) - count(DISTINCT (source_id, external_id)) FROM jobs" "0"

echo
if [[ $FAILS -eq 0 ]]; then echo "E2E PASSED"; else echo "E2E FAILED: $FAILS check(s)"; tail -40 "$WORK/run1.log"; exit 1; fi
