// Mock job boards + mock Anthropic Messages API for the end-to-end test. No real network, no API spend.
//   node tests/e2e/mock-server.mjs [port]
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gmailRoute, telegramRoute, claudeEmail, gmailState } from './mock-gmail.mjs';

const fx = (n) => readFileSync(join(dirname(fileURLToPath(import.meta.url)), '..', 'fixtures', `${n}.json`), 'utf8');
const port = Number(process.argv[2] || 8787);
const calls = {};
let removeJob1001 = false;

const boards = {
  'GET /gh/acmepay': () => {
    const body = JSON.parse(fx('greenhouse'));
    if (removeJob1001) body.jobs = body.jobs.filter((j) => j.id !== 1001);
    return [200, body];
  },
  'GET /gh/acmepay-india': () => [200, JSON.parse(fx('greenhouse_dup'))],
  'GET /lever/creditco': () => [200, JSON.parse(fx('lever'))],
  'GET /ashby/streamco': () => [200, JSON.parse(fx('ashby'))],
  'GET /sr/freshworks': () => [200, JSON.parse(fx('smartrecruiters'))],
  'POST /wd/acme': () => [200, JSON.parse(fx('workday'))],
  'GET /gh/missing': () => [404, { status: 404, error: 'Job not found' }],
  'GET /gh/broken': () => [500, 'oops'],
};

function claude(reqBody, headers) {
  const user = reqBody.messages[0].content;
  const title = (user.match(/"title": "([^"]+)"/) || [])[1] || '';
  calls[title] = (calls[title] || 0) + 1;
  if (!headers['x-api-key']) return [401, { type: 'error', error: { type: 'authentication_error', message: 'missing key' } }];
  if (!reqBody.output_config || reqBody.output_config.format.type !== 'json_schema') return [400, { type: 'error', error: { message: 'expected structured output' } }];
  // First call for this title is overloaded -> exercises n8n retryOnFail.
  if (title.startsWith('SDE 2') && calls[title] === 1) return [529, { type: 'error', error: { type: 'overloaded_error', message: 'Overloaded' } }];
  if (title.startsWith('Java Developer')) {
    return [200, { id: 'msg_r', type: 'message', role: 'assistant', model: reqBody.model, stop_reason: 'refusal',
      stop_details: { type: 'refusal', category: null, explanation: 'test refusal' }, content: [], usage: { input_tokens: 10, output_tokens: 0 } }];
  }
  const strong = /backend|sde|java/i.test(title);
  const out = {
    technical_score: strong ? 37 : 26, experience_score: 18, location_score: 15, company_score: 14,
    company_type: 'fintech', experience_required: null,
    salary_quote: /Software Engineer II/.test(title) ? 'CTC: ₹18 - 26 LPA' : null,
    matched_skills: ['Java', 'Spring Boot', 'Kafka'], missing_skills: ['Go'], red_flags: [],
    verdict: strong ? 'strong_match' : 'good_match', summary: `Mock score for ${title}.`,
  };
  return [200, { id: 'msg_1', type: 'message', role: 'assistant', model: reqBody.model, stop_reason: 'end_turn',
    content: [{ type: 'thinking', thinking: '', signature: 'x' }, { type: 'text', text: JSON.stringify(out) }],
    usage: { input_tokens: 2400, output_tokens: 280, cache_read_input_tokens: 0 } }];
}

createServer((req, res) => {
  let data = '';
  req.on('data', (c) => (data += c));
  req.on('end', () => {
    const key = `${req.method} ${req.url.split('?')[0].toLowerCase()}`;
    let status; let body;
    const routed = gmailRoute(req.method, req.url, req.headers, data) || telegramRoute(req.method, req.url, data);
    if (routed) [status, body] = routed;
    else if (key === 'POST /v1/messages') {
      const reqBody = JSON.parse(data || '{}');
      if (!req.headers['x-api-key']) [status, body] = [401, { error: { message: 'missing key' } }];
      else if (/email assistant/.test(JSON.stringify(reqBody.system || ''))) [status, body] = claudeEmail(reqBody);
      else [status, body] = claude(reqBody, req.headers);
    }
    else if (key === 'POST /control/add-m9') { gmailState.visible.push('m9'); [status, body] = [200, { ok: true }]; }
    else if (key === 'GET /control/gmail') [status, body] = [200, gmailState];
    else if (key === 'POST /control/remove-1001') { removeJob1001 = true; [status, body] = [200, { ok: true }]; }
    else if (key === 'GET /control/calls') [status, body] = [200, calls];
    else if (boards[key]) [status, body] = boards[key]();
    else [status, body] = [404, { error: `no route ${key}` }];
    res.writeHead(status, { 'content-type': typeof body === 'string' ? 'text/plain' : 'application/json' });
    res.end(typeof body === 'string' ? body : JSON.stringify(body));
  });
}).listen(port, '127.0.0.1', () => console.log(`mock listening on ${port}`));
