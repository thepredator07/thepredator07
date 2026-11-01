// Code node "Interpret" (Run once for all items). Input: {email, response} per analysed message.
const { cfg } = $('Load config').first().json;
const NOREPLY = /(no-?reply|do-?not-?reply|notifications?@|mailer-daemon|bounce)/i;

const records = [], interviews = [], drafts = [], notifications = [], people = {};
const threads = {};
let ok = 0, failed = 0;

for (const item of $input.all()) {
  const { email, response } = item.json;
  if (!email) continue;
  const base = {
    gmail_message_id: email.id, gmail_thread_id: email.thread_id, received_at: email.received_at,
    from_email: email.from_email, from_name: email.from_name, subject: email.subject, triage_reason: email.triage_reason,
  };
  const r = LIB.parseEmailAnalysis(response, email, cfg);
  if (!r.ok) {
    failed++;
    records.push(Object.assign(base, { relevance: 'recruiter', status: 'error', last_error: r.error }));
    continue;
  }
  ok++;
  const a = r.analysis;
  const related = a.category !== 'not_job_related';
  records.push(Object.assign(base, {
    relevance: related ? 'recruiter' : 'not_job_related', status: 'done', category: a.category,
    body_excerpt: related ? (email.body || '').slice(0, 4000) : null,
    analysis: related ? Object.assign({}, a, { interviews: r.interviews, reply_needs_user_input: r.reply ? r.reply.needs_user_input : [] }) : { category: a.category },
    model: r.model, input_tokens: r.input_tokens, output_tokens: r.output_tokens,
  }));
  if (!related) continue;

  const prev = threads[email.thread_id];
  const t = {
    gmail_thread_id: email.thread_id, gmail_last_message_id: email.id, company: a.company,
    recruiter_email: NOREPLY.test(a.recruiter.email || '') ? null : a.recruiter.email,
    category: a.category, subject: email.subject, summary: a.summary,
    extracted: { designation: a.designation, location: a.location, salary_quote: a.salary_quote, experience_required: a.experience_required,
                 job_url: a.job_url, questions_asked: a.questions_asked, requested_items: a.requested_items, deadline: a.deadline,
                 company_hint: a.company_hint, recruiter: a.recruiter, interview_round: a.interview_round, meeting_link: a.meeting_link },
    resume_requested: a.requested_items.includes('resume'),
    needs_action: !!r.reply || a.requested_items.length > 0 || a.category === 'interview_invitation' || a.category === 'offer',
    last_message_at: email.received_at, suggested_application_status: a.suggested_application_status,
    needs_user_input: r.reply ? r.reply.needs_user_input : [], urgency: a.urgency, new_messages: 1,
  };
  if (!prev || new Date(prev.last_message_at) <= new Date(t.last_message_at)) {
    t.new_messages = (prev ? prev.new_messages : 0) + 1;
    t.company = t.company || (prev && prev.company);
    threads[email.thread_id] = t;
  } else prev.new_messages += 1;

  if (t.recruiter_email) {
    const p = people[t.recruiter_email] || {};
    people[t.recruiter_email] = {
      email: t.recruiter_email, full_name: a.recruiter.name || p.full_name || null, designation: a.recruiter.title || p.designation || null,
      company: a.recruiter.agency ? null : (a.company || p.company || null), agency: a.recruiter.agency || p.agency || null,
      phone: a.recruiter.phone || p.phone || null, last_interaction_at: email.received_at,
    };
  }
  for (const iv of r.interviews) {
    interviews.push(Object.assign({ gmail_thread_id: email.thread_id, round: a.interview_round, mode: a.interview_mode, meeting_link: a.meeting_link }, iv));
  }
  if (r.reply) {
    const subject = LIB.replySubject(email.subject);
    const to = a.recruiter.email && !NOREPLY.test(a.recruiter.email) ? a.recruiter.email : null;
    if (to) {
      drafts.push({
        gmail_thread_id: email.thread_id, reply_to_message_id: email.id, to_email: to, subject, body: r.reply.body,
        needs_user_input: r.reply.needs_user_input,
        raw: LIB.buildDraftRaw({ to, subject, inReplyTo: email.message_id_header, references: email.references, body: r.reply.body }),
      });
    }
  }
  for (const n of LIB.notificationsFor(email, a, r.interviews, r.reply && drafts.some((d) => d.reply_to_message_id === email.id) ? r.reply : null)) {
    notifications.push(Object.assign({ gmail_thread_id: email.thread_id }, n));
  }
}

const stats = { analysed: ok, failed, drafts: drafts.length, interviews: interviews.length, notifications: notifications.length,
                deferred: $('Triage').first().json.deferred };
return [{ json: {
  records_json: JSON.stringify(records), threads_json: JSON.stringify(Object.values(threads)),
  people_json: JSON.stringify(Object.values(people)), interviews_json: JSON.stringify(interviews),
  drafts_json: JSON.stringify(drafts), notifications_json: JSON.stringify(notifications),
  stats_json: JSON.stringify(stats),
  log_message: `Gmail triage: ${ok} analysed, ${failed} failed, ${drafts.length} drafts, ${interviews.length} interview slots, ${stats.deferred} deferred`,
} }];
