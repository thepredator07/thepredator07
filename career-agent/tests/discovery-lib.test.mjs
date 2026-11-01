// Unit tests for Module A logic. Run: node --test tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const L = require('../n8n/src/discovery-lib.js');

// Use the real seeded settings so regex/config changes are covered by tests.
const seed = readFileSync(join(here, '../db/002_seed.sql'), 'utf8');
const blocks = [...seed.matchAll(/\$json\$([\s\S]*?)\$json\$/g)].map((m) => JSON.parse(m[1]));
const profile = blocks[0];
const cfg = blocks[1];
const fixture = (n) => JSON.parse(readFileSync(join(here, 'fixtures', `${n}.json`), 'utf8'));
const NOW = new Date('2026-11-01T06:00:00Z');
const src = (ats, extra = {}) => ({ id: 1, company_id: 7, company_name: 'AcmePay', ats, board_token: 'acmepay', ...extra });
const run = (ats, body, extra) => L.processSourceResponse(src(ats, extra), { statusCode: 200, body }, cfg, profile, NOW);
const byId = (jobs, id) => jobs.find((j) => j.external_id === id);

test('buildRequest builds one URL per ATS, Workday as POST', () => {
  assert.equal(L.buildRequest(src('greenhouse'), cfg).url, 'https://boards-api.greenhouse.io/v1/boards/acmepay/jobs?content=true');
  assert.equal(L.buildRequest(src('lever'), cfg).url, 'https://api.lever.co/v0/postings/acmepay?mode=json');
  assert.match(L.buildRequest(src('ashby'), cfg).url, /posting-api\/job-board\/acmepay\?includeCompensation=true$/);
  assert.match(L.buildRequest(src('smartrecruiters'), cfg).url, /companies\/acmepay\/postings\?limit=100&country=in$/);
  const wd = L.buildRequest(src('workday', { board_token: 'x.wd1.myworkdayjobs.com|x|Careers', search_text: 'java' }), cfg);
  assert.equal(wd.method, 'POST');
  assert.equal(wd.url, 'https://x.wd1.myworkdayjobs.com/wday/cxs/x/Careers/jobs');
  assert.equal(wd.body.searchText, 'java');
  assert.equal(L.buildRequest(src('lever', { endpoint_url: 'http://mock/lever' }), cfg).url, 'http://mock/lever');
});

test('greenhouse: html decoded, filters applied, salary read only from JD text', () => {
  const { result, jobs } = run('greenhouse', fixture('greenhouse'));
  assert.equal(result.ok, true);
  assert.equal(result.fetched, 6);
  const be = byId(jobs, '1001');
  assert.equal(be.prefilter_pass, true);
  assert.equal(be.matched_location, 'bengaluru');
  assert.deepEqual([be.exp_min, be.exp_max, be.exp_fit, be.exp_in_2_5], [3, 5, 'fit', true]);
  assert.match(be.description_text, /Java and Spring Boot/);
  assert.doesNotMatch(be.description_text, /<|&lt;/);
  assert.equal(be.salary_source, 'jd_text');
  assert.deepEqual([be.salary_min_inr, be.salary_max_inr, be.comp_fit], [1800000, 2600000, 'fit']);
  assert.equal(be.fingerprint, 'acmepay|softwareengineer2backend|bengaluru');

  assert.equal(byId(jobs, '1002').prefilter_reason, 'title_not_relevant');  // frontend
  assert.equal(byId(jobs, '1003').prefilter_reason, 'title_excluded');      // staff
  assert.equal(byId(jobs, '1004').prefilter_reason, 'outside_india');
  assert.equal(byId(jobs, '1005').prefilter_reason, 'stale');               // 60 days old
  assert.equal(byId(jobs, '1006').prefilter_reason, 'experience_over');     // 7+ years
  assert.equal(byId(jobs, '1006').matched_location, 'remote_india');
  assert.equal(byId(jobs, '1002').description_text, null);                  // not stored when filtered out
  assert.equal(result.passed, 1);
});

test('lever: structured INR salary, multi-location, intern excluded', () => {
  const { jobs } = run('lever', fixture('lever'));
  const sde2 = byId(jobs, 'lv-1');
  assert.equal(sde2.prefilter_pass, true);
  assert.equal(sde2.matched_location, 'mumbai');
  assert.equal(sde2.salary_source, 'structured');
  assert.deepEqual([sde2.salary_min_inr, sde2.salary_max_inr, sde2.comp_fit], [2200000, 3000000, 'above']);
  assert.deepEqual([sde2.exp_min, sde2.exp_max], [2, 4]);
  assert.match(sde2.description_text, /Spring Boot/);
  assert.equal(sde2.apply_url, 'https://jobs.lever.co/creditco/lv-1/apply');
  assert.equal(byId(jobs, 'lv-2').prefilter_pass, true);
  assert.equal(byId(jobs, 'lv-2').comp_fit, 'unknown');
  assert.equal(byId(jobs, 'lv-3').prefilter_reason, 'title_excluded');
});

test('ashby: unlisted jobs dropped, below-target salary flagged', () => {
  const { jobs } = run('ashby', fixture('ashby'));
  assert.equal(jobs.length, 1);
  const j = jobs[0];
  assert.equal(j.matched_location, 'remote_india');
  assert.equal(j.is_remote, true);
  assert.equal(j.comp_fit, 'below');
  assert.equal(L.compensationPoints(j), 4);
});

test('smartrecruiters and workday: no description, posted dates, partial listings', () => {
  const sr = run('smartrecruiters', fixture('smartrecruiters'), { board_token: 'Freshworks' });
  assert.equal(sr.result.complete, true);
  assert.equal(byId(sr.jobs, '744000001').prefilter_pass, true);
  assert.equal(byId(sr.jobs, '744000001').job_url, 'https://jobs.smartrecruiters.com/Freshworks/744000001');
  assert.equal(byId(sr.jobs, '744000002').prefilter_pass, false);

  const wd = run('workday', fixture('workday'), { board_token: 'acme.wd1.myworkdayjobs.com|acme|Careers' });
  assert.equal(wd.result.complete, false);                 // 57 total, 2 returned
  const j = byId(wd.jobs, 'R0001');
  assert.equal(j.job_url, 'https://acme.wd1.myworkdayjobs.com/en-US/Careers/job/Bangalore/Software-Engineer-2_R0001');
  assert.equal(j.posted_at, new Date(NOW.getTime() - 3 * 86400000).toISOString());
  assert.equal(j.prefilter_pass, true);
  assert.equal(byId(wd.jobs, 'R0002').prefilter_reason, 'india_other_city');
});

test('HTTP and network failures become failed source results, not crashes', () => {
  const s = src('greenhouse');
  assert.deepEqual(L.processSourceResponse(s, { statusCode: 404, body: {} }, cfg, profile, NOW).result.error, 'HTTP 404');
  assert.match(L.processSourceResponse(s, { error: { message: 'ETIMEDOUT' } }, cfg, profile, NOW).result.error, /ETIMEDOUT/);
  assert.equal(L.processSourceResponse(s, { statusCode: 200, body: '<html>' }, cfg, profile, NOW).result.error, 'invalid JSON body');
});

test('experience extraction ignores non-experience numbers', () => {
  assert.deepEqual(L.extractExperience('Founded 12 years ago. We need 4+ years of experience in Java.'), { min: 4, max: null, text: '4+ years' });
  assert.deepEqual(L.extractExperience('Minimum of 2 years building services').min, 2);
  assert.equal(L.extractExperience('No requirement stated').min, null);
  assert.deepEqual(L.classifyExperience(5, null), { fit: 'stretch', in2to5: true });
  assert.deepEqual(L.classifyExperience(0, 1), { fit: 'under', in2to5: false });
});

test('salary extraction never reads experience ranges as salary', () => {
  assert.equal(L.extractSalaryFromText('3-5 years of experience required'), null);
  assert.equal(L.extractSalaryFromText('Team of 15-20 engineers'), null);
  assert.deepEqual(L.extractSalaryFromText('Budget: 15 to 22 LPA').min, 1500000);
  assert.deepEqual(L.extractSalaryFromText('₹16,00,000 - ₹24,00,000 per annum').max, 2400000);
  assert.equal(L.compFit({ salary_source: 'structured', salary_currency: 'USD' }, profile.target_ctc_lpa), 'non_inr');
});

test('location matching', () => {
  assert.equal(L.matchLocation('Remote - US only', false, cfg).matched, null);
  assert.equal(L.matchLocation('Remote, United States', false, cfg).reason, 'remote_outside_india');
  assert.equal(L.matchLocation('Remote', false, cfg).matched, 'remote_india');
  assert.equal(L.matchLocation('Gurugram, Haryana, India', false, cfg).reason, 'india_other_city');
  assert.equal(L.matchLocation('Singapore; Bengaluru', false, cfg).matched, 'bengaluru');
  assert.equal(L.matchLocation('', false, cfg).reason, 'location_unknown');
  // jsonb re-orders keys (pune before mumbai); the first city in the string must still win
  const reordered = Object.assign({}, cfg, { target_cities: { pune: ['pune'], mumbai: ['mumbai', 'bombay'] } });
  assert.equal(L.matchLocation('Mumbai; Pune', false, reordered).matched, 'mumbai');
});

test('Claude request: structured output, cached system prompt, fallbacks on Opus 5.5', () => {
  const job = byId(run('greenhouse', fixture('greenhouse')).jobs, '1001');
  const r = L.buildClaudeRequest(Object.assign({ id: 5, company_category: 'fintech' }, job), profile, cfg);
  assert.equal(r.url, 'https://api.anthropic.com/v1/messages');
  assert.equal(r.body.model, 'claude-opus-5-5');
  assert.equal(r.body.output_config.format.type, 'json_schema');
  assert.equal(r.body.output_config.effort, 'low');
  assert.equal(r.body.system[0].cache_control.type, 'ephemeral');
  assert.equal(r.body.fallbacks, 'default');
  assert.equal(r.headers['anthropic-beta'], 'server-side-fallback-2026-07-01');
  assert.equal(r.body.temperature, undefined);
  assert.match(r.body.system[0].text, /Jio Platforms/);
  assert.match(r.body.messages[0].content, /payment rails/);
  const haiku = L.buildClaudeRequest(job, profile, Object.assign({}, cfg, { scoring_model: 'claude-haiku-5-5' }));
  assert.equal(haiku.body.fallbacks, undefined);
  assert.equal(haiku.headers['anthropic-beta'], undefined);
});

const modelOut = (o) => ({
  model: 'claude-opus-5-5', stop_reason: 'end_turn', usage: { input_tokens: 2100, output_tokens: 310 },
  content: [{ type: 'thinking', thinking: '' }, { type: 'text', text: JSON.stringify(Object.assign({
    technical_score: 38, experience_score: 19, location_score: 15, company_score: 14, company_type: 'fintech',
    experience_required: '3-5 years', salary_quote: null, matched_skills: ['Java'], missing_skills: [], red_flags: [],
    verdict: 'strong_match', summary: 'Core Java backend role.' }, o)) }],
});

test('score parsing: clamps, deterministic compensation, verified salary quotes only', () => {
  const job = { id: 9, salary_source: 'none', comp_fit: 'unknown', description_text: 'Great role. Compensation: 16-21 LPA plus ESOPs.' };
  const c = Object.assign({}, cfg, { target_ctc_lpa: profile.target_ctc_lpa });

  let r = L.parseClaudeResponse(modelOut({ technical_score: 55 }), job, c);
  assert.equal(r.ok, true);
  assert.equal(r.score.technical, 40);                    // clamped
  assert.equal(r.score.compensation, 5);                  // unknown salary -> neutral
  assert.equal(r.score.total, 40 + 19 + 15 + 14 + 5);

  r = L.parseClaudeResponse(modelOut({ salary_quote: '16-21 LPA' }), job, c);
  assert.equal(r.job_update.salary_source, 'jd_text');
  assert.equal(r.job_update.comp_fit, 'fit');
  assert.equal(r.score.compensation, 10);

  r = L.parseClaudeResponse(modelOut({ salary_quote: '30-40 LPA' }), job, c); // not in the JD: ignored
  assert.deepEqual(r.job_update, {});
  assert.equal(r.score.compensation, 5);

  r = L.parseClaudeResponse(modelOut({}), { id: 1, salary_source: 'none', description_text: '' }, c);
  assert.equal(r.score.technical, 28);                    // no description -> capped
  assert.equal(r.score.verdict, 'good_match');            // ...and never a strong match

  assert.match(L.parseClaudeResponse({ stop_reason: 'refusal', stop_details: { category: 'cyber' }, content: [] }, job, c).error, /refusal/);
  assert.equal(L.parseClaudeResponse({ error: { message: '429 rate limited' } }, job, c).ok, false);
  assert.equal(L.parseClaudeResponse({ stop_reason: 'end_turn', content: [{ type: 'text', text: 'not json' }] }, job, c).ok, false);
});
