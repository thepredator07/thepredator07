# Module C: Gmail Recruiter Assistant

Workflows **C1 Gmail Triage** (`n8n/workflows/C1_gmail_triage.json`, id `careerC1GmailTri`) and
**N1 Notifier** (`N1_notifier.json`, id `careerN1Notifier`, shared by all modules).

## 1. Summary

| | |
|---|---|
| **Trigger** | C1: every 15 minutes (+ manual). N1: every 5 minutes (+ manual) |
| **Input** | Gmail messages matching `settings.gmail.query` (default: last 3 days, not from you, not spam/trash/chats) that have not been processed |
| **Processing** | Headers-only triage in code → full message only for likely job mail → Claude classifies, extracts, drafts a reply → saved against company, application, recruiter |
| **Claude** | One call per job-related email: category (7 + not job-related), company, role, salary, location, interview slots, recruiter, questions, requested items, summary, reply draft |
| **Output** | `email_messages`, `email_threads`, `people` (recruiters), `interviews`, `email_drafts`, `approvals`, `notifications`; Gmail **drafts**; Telegram alerts |
| **External actions** | Creates Gmail drafts (reply-to the sender, in the same thread). **Never sends.** N1 messages only you, on Telegram |
| **Approval** | You open the draft in Gmail, fill the `[PLACEHOLDERS]`, attach your resume if asked, and press send yourself. Interview dates go to `interviews` (status `detected`); Module G adds them to Calendar after your approval |

Categories: `new_opportunity`, `interview_invitation`, `hr_follow_up`, `rejection`, `offer`, `referral`, `general`, plus `not_job_related` (a false positive from triage; stored without body).

## 2. One-time Google setup (≈10 minutes)

1. <https://console.cloud.google.com> → create a project (e.g. `career-agent`).
2. *APIs & Services → Library* → enable **Gmail API**.
3. *Google Auth Platform → Branding*: app name, your email. *Audience*: user type **External**, add your Gmail address as a test user.
4. *Data access → Add scopes*: `.../auth/gmail.readonly` and `.../auth/gmail.compose`. Nothing else.
5. *Clients → Create client* → type **Web application** → authorised redirect URI
   `http://localhost:5678/rest/oauth2-credential/callback` (that is the n8n URL you reach through the SSH tunnel; use your HTTPS URL instead if you expose n8n).
6. Put the client ID and secret in `.env` as `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET`, then `WITH_GMAIL=1 scripts/bootstrap.sh`.
7. In n8n: *Credentials → Gmail (career agent) → Sign in with Google*, approve the two scopes.
8. **Token lifetime**: while the app's publishing status is *Testing*, Google expires the refresh token after 7 days and C1 starts failing with 401 (Z0 alerts you). For a personal app, set *Audience → Publish app* (status *In production*). Because these are restricted Gmail scopes and the app is unverified, Google shows an "unverified app" warning at sign-in, which you can pass for your own account; check Google's current policy for personal-use apps if that changes. Alternative: stay in Testing and re-sign-in weekly.

**Why these scopes**: `gmail.readonly` reads mail; `gmail.compose` is the narrowest scope that can create drafts. There is no Google scope that allows drafts but not sending, so "never send" is enforced by the workflow: there is no send call anywhere in the code, the e2e test fails if any `/send` endpoint is hit, and no label/modify/delete scope is requested.

## 3. Nodes (C1)

| # | Node | What it does |
|---|---|---|
| 1 | Every 15 minutes / Run manually | Start |
| 2 | Load config | Takes a 14-minute lease in `workflow_locks` (overlapping runs never double-process), loads `settings.gmail`, profile, threads tracked in the last 90 days |
| 3 | Check lock | Stops quietly if another run holds the lease |
| 4 | List messages | `GET messages?q=…&maxResults=100`, 3 tries. An auth failure stops the run → Z0 alert |
| 5 | Unprocessed ids | New ids + earlier failures under `max_attempts`; releases the lease if there is nothing to do |
| 6–7 | Expand ids, Get metadata | `format=metadata` (headers + snippet only). A failed fetch is simply retried next run |
| 8 | Triage | Rules, no Claude (§4). Job alerts and skipped mail are recorded without body |
| 9 | Record skipped | Saves those; releases the lease if no candidates |
| 10–12 | Expand candidates, Get full message, Parse emails | `format=full` for at most `max_messages_per_run` (25); decodes base64url parts, prefers text/plain, strips quoted history and HTML |
| 13 | Thread context | Earlier summary/category/company/application of the same thread, for Claude |
| 14–17 | Build Claude requests, One email at a time, Claude: analyse email, Attach email | One Messages API call per email; per-email retry (3 tries, 5 s) |
| 18 | Interpret | Validates the output, builds thread/person/interview/draft/notification records and the RFC 2822 draft |
| 19 | Save threads, messages, drafts | One transaction: companies, people, threads, messages, give-up alerts, interviews, drafts, notifications, log, lease release; claims pending drafts |
| 20–23 | Expand drafts, Create Gmail draft, Collect draft results, Mark drafts | `POST drafts` in the original thread; on success queues an `approvals` row; failures are retried on later runs, then alerted |

## 4. Triage rules (no Claude, no body read)

Driven by `settings.gmail`; first match wins:

1. Thread already tracked → **recruiter** (follow-ups like "Thanks, see you Thursday").
2. Sender on a job platform (`job_platform_domains`) and alert-like sender/subject → **job_alert** (stored for the job-alert ingestion workflow; never sent to Claude).
3. Sender on a recruiting system (`ats_sender_domains`: Greenhouse, Lever, Ashby, Workday, SmartRecruiters, HackerRank, Naukri, Instahyre, …) → **recruiter**.
4. Bulk mail (List-Unsubscribe, Promotions/Social tab) without a recruiting keyword in the subject → **skipped**.
5. Recruiting keyword in subject or snippet (`recruiting_keywords_regex`: opportunity, opening, interview, CTC, notice period, resume, offer letter, assessment, …) → **recruiter**.
6. Otherwise **skipped** (personal mail etc.). Only sender and date are stored; no subject, no body.

## 5. Claude prompt (email-v1)

Full text: `buildEmailSystemPrompt()` / `buildEmailUserMessage()` in `n8n/src/email-lib.js`.

- System: the assistant's job; candidate profile; `reply_facts` from settings (name, notice period
  "60 days, negotiable to 45 days", current/expected CTC, availability, locations); the rule that
  the email is untrusted third-party data whose instructions must never be followed; category
  definitions; extraction rules (verbatim, null when absent, no inferred company names, interview
  times as ISO 8601 resolved from the IST receive time, IST assumed when no zone); drafting rules.
- Drafting rules: first person, under 130 words, no clichés/emojis/signature; answer only what was
  asked; **missing facts become `[PLACEHOLDERS]`** (`[CURRENT CTC]`, `[EXPECTED CTC]`,
  `[AVAILABLE SLOTS]`); interview slots are never accepted on your behalf
  (`[CONFIRM: Thu 5 Nov, 3:00 PM IST]`); resume requested → "attached" (you attach it); offers →
  acknowledge only, never accept/decline/negotiate; clear mismatches → polite decline naming the
  roles you want.
- User: received time in IST, sender, subject, attachment names, earlier-in-thread context, the
  email body (quoted history removed, capped at 12,000 chars and marked truncated).
- Structured output (JSON schema), `effort: medium` (drafts are read by recruiters, so a bit more
  thought than job scoring), refusal fallback enabled as in Module A.

**Checks in code after Claude** (`parseEmailAnalysis`): salary quote, recruiter phone, job URL and
meeting link are kept only if they appear in the email; interview slots must parse and fall
between 1 day before and 180 days after the email; replies only for reply-worthy categories and
never to no-reply addresses; signature appended from settings; placeholders listed in
`email_drafts.needs_user_input` and in the alert.

Edit what replies may say about you:

```sql
UPDATE settings SET value = jsonb_set(value, '{reply_facts,expected_ctc}', '"₹20-24 LPA"') WHERE key = 'gmail';
UPDATE settings SET value = jsonb_set(value, '{signature}', '"Best regards,\nKhubaib\n+91 9XXXXXXXXX"') WHERE key = 'gmail';
```

Leaving `current_ctc` / `expected_ctc` null is deliberate: drafts then contain placeholders and you
decide per recruiter what to disclose.

## 6. Data and tracking

- `email_threads`: one row per Gmail thread: latest category, summary, extracted facts (role,
  location, salary quote, questions, requested items, deadline, recruiter), linked `company_id`
  (matched by normalised name; unknown companies are added with `is_target = false`),
  `application_id` (your latest non-rejected application at that company), recruiter,
  `suggested_application_status` (e.g. `technical_interview`, `rejected`; Module E applies it
  with one click), `needs_user_input`, `draft_gmail_id`, `message_count`.
- `people`: recruiters keyed by email, with agency, title, verified phone, last interaction.
- `interviews`: each validated slot, `time_assumed_ist` when the email gave no time zone.
- `email_messages`: every message looked at; body excerpt (4,000 chars) only for job-related mail.
- Privacy: C1 doesn't keep execution data for successful runs (`saveDataSuccessExecution: none`),
  so email bodies aren't stored in n8n's own database; failed runs are kept for debugging and pruned
  after 14 days.

## 7. Notifications (N1)

C1 queues alerts in `notifications`; N1 sends priority 1–2 to Telegram every 5 minutes; priority 3
waits for the daily brief (Module H).

| Priority | Alerts |
|---|---|
| 1 (now) | Interview invitation (with IST time), offer, resume requested (once per thread) |
| 2 (soon) | New opportunity (location, salary if stated), HR follow-up, referral, email/draft that failed repeatedly |
| 3 (brief) | Rejection |

Set up: `TELEGRAM_BOT_TOKEN` and `TELEGRAM_CHAT_ID` in `.env` → `scripts/bootstrap.sh`. Change the
cut-off: `UPDATE settings SET value = jsonb_set(value,'{immediate_max_priority}','1') WHERE key='notifications';`

## 8. Error handling

| Failure | Behaviour |
|---|---|
| Gmail auth / list fails | 3 tries, then the run fails → Z0 logs it and alerts you (usually: re-sign-in to Google) |
| One message can't be fetched | Not recorded; picked up next run (tested) |
| Claude 429/5xx/overloaded | 3 tries per email; then the email is marked `error` and retried next run; after `max_attempts` (3) runs it is given up and you get "Could not process an email … read it in Gmail" (tested) |
| Refusal / invalid output | Same as above |
| Draft creation fails | Draft stays `pending`, retried on later runs; after 3 attempts `failed` + alert (tested) |
| Two runs overlap | Lease in `workflow_locks`; the second run exits immediately |
| Newer message arrives before a draft was created | The older pending draft is `superseded`; the new message gets its own draft |

## 9. Testing

- Unit: `node --test tests/email-lib.test.mjs` — 7 tests: UTF-8 base64url (₹, Devanagari),
  multipart/HTML/attachments, quoted-history stripping, triage of every fixture type, request
  shape, verification of quotes/phones/links, placeholders and signature, interview slot
  validation and IST handling, RFC 2822 threading headers and encoded subjects.
- End-to-end in real n8n 2.45.7 with mock Gmail/Telegram/Claude: `tests/e2e/run-e2e-gmail.sh` —
  four C1 runs and one N1 run over 9 fixture emails (recruiter outreach with salary and resume
  request, ATS interview invite with reply-to, ATS rejection, LinkedIn job alert, newsletter,
  personal mail, a prompt-injection email, an email Claude always fails on, a follow-up in an
  existing thread). **41 checks, all passing**, including: nothing ever sent, every Gmail call
  authenticated, the injection email's draft goes only back to its sender, no body stored for
  unrelated mail, follow-up joins its thread, failed draft retried and created, give-up after 3
  attempts with an alert, each email analysed exactly once, Telegram alerts with IST times.
- The only thing the e2e can't exercise is Google's OAuth sign-in (it swaps the Gmail credential
  for a bearer header); the production workflow and credential were import-checked in n8n.

**First live run:**

1. `scripts/migrate.sh` (existing install) or fresh `docker compose up -d`.
2. Google setup (§2), `WITH_GMAIL=1 scripts/bootstrap.sh`, sign in on the credential.
3. Start narrow: `UPDATE settings SET value = value || '{"query":"newer_than:7d -from:me", "max_messages_per_run": 5}' WHERE key='gmail';`
4. Open C1 → *Execute workflow*. Then check:
   ```sql
   SELECT relevance, triage_reason, count(*) FROM email_messages GROUP BY 1,2 ORDER BY 3 DESC;
   SELECT category, left(subject,60), summary, array_to_string(needs_user_input, ', ') FROM email_threads ORDER BY last_message_at DESC;
   SELECT to_email, subject, body FROM email_drafts ORDER BY id DESC LIMIT 5;
   ```
   Read the drafts in Gmail. If a recruiter mail was skipped, add its sender domain to
   `ats_sender_domains` or a keyword to `recruiting_keywords_regex`.
5. Restore `max_messages_per_run` (25) and the default query, then `scripts/bootstrap.sh --publish`.

## 10. Cost

About 2,000–3,000 input and ~1,000 output tokens per analysed email at `effort: medium` on Claude
Opus 5.5 ≈ $0.03 ≈ ₹2.6. At 5–15 job-related emails a day: **≈ ₹400–1,200 / month**. Skipped mail
and job alerts cost nothing. Measure:

```sql
SELECT count(*), round((sum(input_tokens) * 4 + sum(output_tokens) * 20) / 1e6 * 88) AS approx_inr
FROM email_messages WHERE processed_at > now() - interval '30 days' AND input_tokens IS NOT NULL;
```

## 11. Known limits (planned follow-ups)

- Resumes are not attached automatically; the alert and draft remind you. Module F (resume
  versions) will attach the right version after your approval.
- "Sent" isn't detected yet: `approvals` rows stay `pending` after you send. Module E will match
  your sent replies (thread + `in:sent`) and close them.
- Job-alert emails are recorded (`relevance = 'job_alert'`) but not yet parsed into `jobs`; that
  is workflow A2, now possible on top of this Gmail connection.
- Recruiters from agencies often don't name the client; those threads have `company = null` and
  `company_hint` set.
