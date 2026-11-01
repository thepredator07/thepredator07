#!/usr/bin/env bash
# End-to-end test of C1 Gmail Triage and N1 Notifier in a real n8n, against PostgreSQL and a mock
# Gmail API / Telegram Bot API / Claude API. Same requirements and env vars as run-e2e.sh.
#
# The only difference from production is authentication: the Gmail OAuth2 credential is swapped for
# a header credential (OAuth needs a browser consent), everything else is the shipped workflow.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
N8N_BIN="${N8N_BIN:-n8n}"
MOCK_PORT="${MOCK_PORT:-8788}"
DB="${E2E_DB:-career_e2e_gmail}"
WORK="$(mktemp -d)"
export N8N_USER_FOLDER="$WORK/n8n"
export N8N_ENCRYPTION_KEY="e2e-test-key-not-secret"
export N8N_DIAGNOSTICS_ENABLED=false N8N_RUNNERS_ENABLED=true GENERIC_TIMEZONE=Asia/Kolkata
MOCK="http://127.0.0.1:$MOCK_PORT"
FAILS=0

cleanup() { [[ -n "${MOCK_PID:-}" ]] && kill "$MOCK_PID" 2>/dev/null || true; rm -rf "$WORK"; }
trap cleanup EXIT

q() { psql -X -q -t -A -d "$DB" -c "$1"; }
check() {
  local got; got="$(q "$2")"
  if [[ "$got" == "$3" ]]; then echo "  ok   $1"; else echo "  FAIL $1: expected '$3', got '$got'"; FAILS=$((FAILS+1)); fi
}
mock() { # mock "<description>" "<js expression over state s>" "<expected>"
  local got; got="$(curl -s "$MOCK/control/gmail" | node -e "const s=JSON.parse(require('fs').readFileSync(0,'utf8')); console.log($2)")"
  if [[ "$got" == "$3" ]]; then echo "  ok   $1"; else echo "  FAIL $1: expected '$3', got '$got'"; FAILS=$((FAILS+1)); fi
}

echo "== mock server"
node "$HERE/mock-server.mjs" "$MOCK_PORT" & MOCK_PID=$!
sleep 1

echo "== database $DB"
psql -X -q -d postgres -c "DROP DATABASE IF EXISTS $DB" -c "CREATE DATABASE $DB"
for f in "$ROOT"/db/0*.sql; do psql -X -q -v ON_ERROR_STOP=1 -d "$DB" -f "$f"; done
psql -X -q -v ON_ERROR_STOP=1 -d "$DB" <<SQL
UPDATE settings SET value = value || '{"gmail_base_url": "$MOCK"}'::jsonb WHERE key = 'gmail';
UPDATE settings SET value = value || '{"anthropic_base_url": "$MOCK"}'::jsonb WHERE key = 'discovery';
UPDATE settings SET value = value || '{"telegram_chat_id": "424242"}'::jsonb WHERE key = 'notifications';
INSERT INTO applications (company_id, role, status) VALUES ((SELECT id FROM companies WHERE name = 'CRED'), 'Software Engineer II', 'applied');
SQL

echo "== n8n credentials + workflows"
cat > "$WORK/creds.json" <<JSON
[
 {"id":"careerPostgres01","name":"Career Postgres","type":"postgres",
  "data":{"host":"${PGHOST:-127.0.0.1}","port":${PGPORT:-5432},"database":"$DB","user":"${PGUSER:-postgres}","password":"${PGPASSWORD:-}","ssl":"disable","allowUnauthorizedCerts":false}},
 {"id":"anthropicApiKey1","name":"Anthropic API key","type":"httpHeaderAuth","data":{"name":"x-api-key","value":"test-key"}},
 {"id":"gmailTestBearer1","name":"Gmail test bearer","type":"httpHeaderAuth","data":{"name":"Authorization","value":"Bearer test-token"}},
 {"id":"telegramBotApi01","name":"Telegram bot","type":"telegramApi","data":{"accessToken":"TESTTOKEN","baseUrl":"$MOCK"}}
]
JSON
node -e '
const fs = require("fs");
const wf = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
for (const n of wf.nodes) if (n.parameters && n.parameters.nodeCredentialType === "gmailOAuth2") {
  Object.assign(n.parameters, { authentication: "genericCredentialType", genericAuthType: "httpHeaderAuth" });
  delete n.parameters.nodeCredentialType;
  n.credentials = { httpHeaderAuth: { id: "gmailTestBearer1", name: "Gmail test bearer" } };
}
fs.writeFileSync(process.argv[2], JSON.stringify(wf));' "$ROOT/n8n/workflows/C1_gmail_triage.json" "$WORK/C1_test.json"
imp() { "$N8N_BIN" "$@" >>"$WORK/import.log" 2>&1 || { tail -30 "$WORK/import.log"; exit 1; }; }
imp import:credentials --input="$WORK/creds.json"
imp import:workflow --input="$ROOT/n8n/workflows/Z0_error_handler.json"
imp import:workflow --input="$WORK/C1_test.json"
imp import:workflow --input="$ROOT/n8n/workflows/N1_notifier.json"

run() { "$N8N_BIN" execute --id "$1" > "$WORK/$2.log" 2>&1 || { echo "n8n execute $1 failed:"; tail -60 "$WORK/$2.log"; exit 1; }; }

echo "== C1 run 1"
run careerC1GmailTri c1-1
check "messages recorded (m6 metadata failed -> retried later)" "SELECT count(*) FROM email_messages" "7"
check "job alert recorded without Claude" "SELECT relevance||':'||triage_reason||':'||status FROM email_messages WHERE gmail_message_id='m4'" "job_alert:job_alert:skipped"
check "newsletter skipped, nothing personal stored" "SELECT relevance||':'||triage_reason||':'||COALESCE(subject,'-')||':'||COALESCE(body_excerpt,'-') FROM email_messages WHERE gmail_message_id='m5'" "skipped:bulk_mail:-:-"
check "categories" "SELECT string_agg(gmail_message_id||'='||category, ',' ORDER BY gmail_message_id) FROM email_messages WHERE status='done'" "m1=new_opportunity,m2=interview_invitation,m3=rejection,m7=new_opportunity"
check "Claude failure counted as attempt 1" "SELECT status||':'||attempts FROM email_messages WHERE gmail_message_id='m8'" "error:1"
check "threads tracked" "SELECT count(*) FROM email_threads" "4"
check "opportunity linked to known company" "SELECT c.name FROM email_threads t JOIN companies c ON c.id=t.company_id WHERE gmail_thread_id='m1'" "Razorpay"
check "interview thread linked to the CRED application" "SELECT a.role FROM email_threads t JOIN applications a ON a.id=t.application_id WHERE gmail_thread_id='m2'" "Software Engineer II"
check "salary quote verified and stored" "SELECT extracted->>'salary_quote' FROM email_threads WHERE gmail_thread_id='m1'" "CTC: 18-24 LPA"
check "resume request flagged" "SELECT resume_requested FROM email_threads WHERE gmail_thread_id='m1'" "t"
check "recruiters stored (no-reply senders excluded)" "SELECT string_agg(email, ',' ORDER BY email) FROM people" "anita.k@cred.club,hr@quickhire.biz,priya@talentcorp.in"
check "agency recruiter keeps agency + verified phone" "SELECT agency||':'||phone FROM people WHERE email='priya@talentcorp.in'" "TalentCorp Recruitment:+91 98765 43210"
check "interview slot stored in UTC with link" "SELECT scheduled_at AT TIME ZONE 'UTC'||'|'||meeting_link||'|'||status||'|'||(application_id IS NOT NULL) FROM interviews" "2026-11-05 09:30:00|https://meet.google.com/abc-defg-hij|detected|true"
check "drafts: 2 created, 1 pending after a 503" "SELECT string_agg(gmail_thread_id||'='||status, ',' ORDER BY gmail_thread_id) FROM email_drafts" "m1=created,m2=pending,m7=created"
check "no draft for the rejection" "SELECT count(*) FROM email_drafts WHERE gmail_thread_id='m3'" "0"
check "draft placeholders recorded" "SELECT array_to_string(needs_user_input, ',') FROM email_drafts WHERE gmail_thread_id='m1'" "[CURRENT CTC]"
check "approval queued per created draft" "SELECT count(*) FROM approvals WHERE kind='send_email' AND status='pending'" "2"
check "notifications queued" "SELECT string_agg(kind||':'||priority, ',' ORDER BY kind) FROM notifications" "interview:1,opportunity:2,opportunity:2,rejection:3,resume_requested:1"
check "run logged" "SELECT level FROM execution_log WHERE workflow='C1 Gmail Triage'" "warn"
check "lease released" "SELECT locked_until <= now() FROM workflow_locks WHERE name='C1'" "t"
mock "Gmail drafts created in the mock" "s.drafts.length" "2"
mock "draft threaded to m1 with signature + placeholder" "(d=>/^To: priya@talentcorp.in/.test(d.raw)&&/In-Reply-To: <m1@mail.example>/.test(d.raw)&&/Subject: Re: Opportunity/.test(d.raw))(s.drafts.find(d=>d.threadId==='m1'))" "true"
mock "injection email: draft only goes back to its sender" "s.drafts.filter(d=>d.threadId==='m7').map(d=>d.raw.match(/^To: (.*)\r/)[1]).join()" "hr@quickhire.biz"
mock "nothing was ever sent" "s.sends.length" "0"
mock "every Gmail call was authenticated" "s.unauthorized" "0"

echo "== N1 notifier"
run careerN1Notifier n1-1
mock "priority 1-2 notifications sent to Telegram" "s.telegram.length+':'+s.telegram.every(t=>t.chat_id==='424242'&&t.token==='TESTTOKEN')" "4:true"
mock "interview message mentions the IST time" "s.telegram.some(t=>/Interview: CRED/.test(t.text)&&/2026-11-05 15:00 IST/.test(t.text))" "true"
check "sent notifications marked, rejection left for the daily brief" "SELECT count(*) FILTER (WHERE sent_at IS NOT NULL)||'/'||count(*) FROM notifications" "4/5"

echo "== C1 run 2 (follow-up arrives in m1's thread)"
curl -s -X POST "$MOCK/control/add-m9" >/dev/null
run careerC1GmailTri c1-2
check "metadata failure retried: m6 now recorded as skipped" "SELECT triage_reason FROM email_messages WHERE gmail_message_id='m6'" "no_recruiting_signal"
check "follow-up joined the existing thread" "SELECT message_count||':'||category FROM email_threads WHERE gmail_thread_id='m1'" "2:hr_follow_up"
check "earlier messages not re-analysed" "SELECT count(*) FROM email_messages WHERE status='done'" "5"
check "pending draft retried and created; follow-up drafted" "SELECT string_agg(gmail_thread_id||':'||reply_to_message_id||'='||status, ',' ORDER BY reply_to_message_id) FROM email_drafts" "m1:m1=created,m2:m2=created,m7:m7=created,m1:m9=created"
check "resume alert not repeated for the reminder" "SELECT count(*) FROM notifications WHERE kind='resume_requested'" "1"
check "Claude failure attempt 2" "SELECT attempts FROM email_messages WHERE gmail_message_id='m8'" "2"
mock "4 Gmail drafts in total" "s.drafts.length" "4"

echo "== C1 run 3 and 4 (give up on the failing email)"
run careerC1GmailTri c1-3
run careerC1GmailTri c1-4
check "gave up after 3 attempts" "SELECT status||':'||attempts FROM email_messages WHERE gmail_message_id='m8'" "error:3"
check "user told to read it manually" "SELECT count(*) FROM notifications WHERE kind='email_failed'" "1"
mock "no further Claude calls after giving up (3 runs x 3 tries)" "s.calls['claude:Opportunity at FlakyCo - FAIL-ME']" "9"
mock "each other email analysed exactly once" "['Opportunity: Backend Engineer (Java) at Razorpay - Bengaluru','Interview invitation: Software Engineer II at CRED','Your application to Zeta','Urgent hiring: Java role'].map(k=>s.calls['claude:'+k]).join()" "1,1,1,1"
check "lease released when there was nothing to do" "SELECT locked_until <= now() FROM workflow_locks WHERE name='C1'" "t"
mock "still nothing sent" "s.sends.length" "0"

echo
if [[ $FAILS -eq 0 ]]; then echo "E2E (Gmail) PASSED"; else echo "E2E (Gmail) FAILED: $FAILS check(s)"; tail -60 "$WORK/c1-1.log"; exit 1; fi
