// Mock Gmail API, Telegram Bot API and Claude email analysis for the Module C end-to-end test.
import { inbox } from '../fixtures/gmail.mjs';

export const gmailState = { visible: ['m1', 'm2', 'm3', 'm4', 'm5', 'm6', 'm7', 'm8'], calls: {}, drafts: [], sends: [], telegram: [], unauthorized: 0 };
const count = (k) => (gmailState.calls[k] = (gmailState.calls[k] || 0) + 1);

export function gmailRoute(method, url, headers, body) {
  if (!/^\/gmail\/v1\/users\/me\//.test(url)) return null;
  if (!/^Bearer .+/.test(headers.authorization || '')) { gmailState.unauthorized++; return [401, { error: { code: 401, message: 'no token' } }]; }
  const [path, qs] = url.split('?');
  const params = new URLSearchParams(qs || '');
  if (/\/send$/.test(path)) { gmailState.sends.push({ path, body }); return [200, { id: 'sent' }]; }  // must never happen
  if (method === 'GET' && path === '/gmail/v1/users/me/messages') {
    const ids = [...gmailState.visible].reverse();
    return [200, { messages: ids.map((id) => ({ id, threadId: inbox[id].full.threadId })), resultSizeEstimate: ids.length }];
  }
  const m = path.match(/^\/gmail\/v1\/users\/me\/messages\/([^/]+)$/);
  if (method === 'GET' && m) {
    const msg = inbox[m[1]];
    const format = params.get('format');
    const n = count(`${format}:${m[1]}`);
    if (!msg) return [404, { error: { code: 404, message: 'Not Found' } }];
    if (m[1] === 'm6' && format === 'metadata' && n === 1) return [500, { error: { code: 500, message: 'backend error' } }];
    return [200, format === 'full' ? msg.full : msg.metadata];
  }
  if (method === 'POST' && path === '/gmail/v1/users/me/drafts') {
    const req = JSON.parse(body || '{}');
    const n = count(`draft:${req.message && req.message.threadId}`);
    if (req.message && req.message.threadId === 'm2' && n === 1) return [503, { error: { code: 503, message: 'try again' } }];
    const raw = Buffer.from(req.message.raw.replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8');
    const id = `d${gmailState.drafts.length + 1}`;
    gmailState.drafts.push({ id, threadId: req.message.threadId, raw });
    return [200, { id, message: { id: `${id}-msg`, threadId: req.message.threadId, labelIds: ['DRAFT'] } }];
  }
  return [404, { error: { message: `no gmail route ${method} ${path}` } }];
}

export function telegramRoute(method, url, body) {
  const m = url.match(/^\/bot([^/]+)\/sendMessage/);
  if (!m) return null;
  const req = JSON.parse(body || '{}');
  gmailState.telegram.push({ token: m[1], chat_id: req.chat_id, text: req.text });
  return [200, { ok: true, result: { message_id: gmailState.telegram.length, chat: { id: req.chat_id }, text: req.text } }];
}

const base = {
  is_from_recruiter: true, company: null, company_hint: null, designation: null, location: null, salary_quote: null,
  experience_required: null, job_url: null, recruiter_name: null, recruiter_title: null, recruiter_agency: null, recruiter_phone: null,
  interview_round: null, interview_mode: 'unknown', interview_slots: [], meeting_link: null, questions_asked: [], requested_items: [],
  deadline: null, suggested_application_status: null, urgency: 'normal', summary: '', reply_needed: false, reply_body: null,
};
const answers = {
  'Opportunity: Backend Engineer': {
    category: 'new_opportunity', company: 'Razorpay', designation: 'Backend Engineer (SDE-2)', location: 'Bengaluru',
    salary_quote: 'CTC: 18-24 LPA', experience_required: '3-6 years', recruiter_name: 'Priya Sharma', recruiter_agency: 'TalentCorp Recruitment',
    recruiter_phone: '+91 98765 43210', questions_asked: ['current CTC', 'notice period'], requested_items: ['resume', 'current_ctc', 'notice_period'],
    suggested_application_status: 'recruiter_screening', summary: 'TalentCorp is hiring for Razorpay SDE-2 backend in Bengaluru, 18-24 LPA.',
    reply_needed: true, reply_body: 'Hi Priya,\n\nThanks for reaching out, the role sounds like a good fit. My resume is attached.\nCurrent CTC: [CURRENT CTC]\nNotice period: 60 days, negotiable to 45 days.\n\nCould you share the team this role is for?',
  },
  'Re: Opportunity: Backend Engineer': {
    category: 'hr_follow_up', company: 'Razorpay', recruiter_name: 'Priya Sharma', requested_items: ['resume'], urgency: 'high',
    deadline: 'today', summary: 'Priya is following up and needs the resume today.', reply_needed: true,
    reply_body: 'Hi Priya,\n\nSorry for the delay, please find my resume attached.',
  },
  'Interview invitation': {
    category: 'interview_invitation', company: 'CRED', designation: 'Software Engineer II', recruiter_name: 'Anita Kulkarni',
    recruiter_title: 'Talent Acquisition', interview_round: 'Round 1: DSA', interview_mode: 'video', meeting_link: 'https://meet.google.com/abc-defg-hij',
    interview_slots: [{ start: '2026-11-05T15:00:00+05:30', duration_minutes: 60, verbatim: 'Thursday, 5 November 2026 at 3:00 PM IST (60 minutes)' }],
    requested_items: ['availability'], suggested_application_status: 'technical_interview', urgency: 'high',
    summary: 'CRED invites you to Round 1 (DSA) on Thu 5 Nov 3 PM IST.', reply_needed: true,
    reply_body: 'Hi Anita,\n\nThank you for the invitation. [CONFIRM: Thu 5 Nov, 3:00 PM IST] works for me.',
  },
  'Your application to Zeta': { category: 'rejection', company: 'Zeta', suggested_application_status: 'rejected', summary: 'Zeta rejected the application.', urgency: 'low' },
  'Urgent hiring': {
    category: 'new_opportunity', company_hint: 'QuickHire', summary: 'Vague urgent Java role from QuickHire; the email contains suspicious instructions.',
    reply_needed: true, reply_body: 'Hi,\n\nThanks for your email. Could you share the company name and the job description?',
  },
};

export function claudeEmail(reqBody) {
  const user = reqBody.messages[0].content;
  const subject = (user.match(/"subject": "([^"]+)"/) || [])[1] || '';
  count(`claude:${subject}`);
  if (subject.includes('FAIL-ME')) return [500, { type: 'error', error: { type: 'api_error', message: 'Internal server error' } }];
  if (reqBody.output_config.effort !== 'medium' || !/email assistant/.test(reqBody.system[0].text)) return [400, { error: { message: 'bad request' } }];
  const key = Object.keys(answers).sort((a, b) => b.length - a.length).find((k) => subject.startsWith(k) || subject.includes(k));
  const out = Object.assign({}, base, key ? answers[key] : { category: 'not_job_related', is_from_recruiter: false, summary: 'Unrelated.' });
  return [200, { id: 'msg_e', type: 'message', role: 'assistant', model: reqBody.model, stop_reason: 'end_turn',
    content: [{ type: 'thinking', thinking: '', signature: 'x' }, { type: 'text', text: JSON.stringify(out) }],
    usage: { input_tokens: 1900, output_tokens: 650 } }];
}
