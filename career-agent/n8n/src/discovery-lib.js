// Module A pure logic: request building, ATS normalisation, pre-filtering, salary/experience
// extraction, Claude request building and response parsing.
//
// This file is the single source of truth. scripts/build-workflows.mjs inlines it into the
// n8n Code nodes, and tests/discovery-lib.test.mjs unit-tests it with node:test.
// Keep it dependency-free (no require): n8n Code nodes block most modules by default.

const DAY_MS = 24 * 60 * 60 * 1000;
const LAKH = 100000;

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------
function decodeEntities(s) {
  return String(s)
    .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'").replace(/&nbsp;/g, ' ')
    .replace(/&#(\d+);/g, (_, n) => String.fromCharCode(Number(n)))
    .replace(/&amp;/g, '&');
}

function htmlToText(html) {
  if (!html) return '';
  let s = decodeEntities(html); // Greenhouse double-escapes its HTML
  s = s.replace(/<(script|style)[\s\S]*?<\/\1>/gi, ' ')
       .replace(/<\s*(br|\/p|\/div|\/li|\/h\d)\s*\/?>/gi, '\n')
       .replace(/<li[^>]*>/gi, '\n- ')
       .replace(/<[^>]+>/g, ' ');
  s = decodeEntities(s);
  return s.replace(/[ \t ]+/g, ' ').replace(/\s*\n\s*/g, '\n').replace(/\n{3,}/g, '\n\n').trim();
}

function toDate(v) {
  if (v === null || v === undefined || v === '') return null;
  const d = typeof v === 'number' ? new Date(v) : new Date(String(v));
  return isNaN(d.getTime()) ? null : d;
}

// Workday: "Posted Today", "Posted Yesterday", "Posted 3 Days Ago", "Posted 30+ Days Ago"
function workdayPostedOn(text, now) {
  if (!text) return null;
  const t = String(text).toLowerCase();
  if (t.includes('today')) return new Date(now.getTime());
  if (t.includes('yesterday')) return new Date(now.getTime() - DAY_MS);
  const m = t.match(/(\d+)\s*(\+)?\s*days?/);
  if (m) return new Date(now.getTime() - (Number(m[1]) + (m[2] ? 1 : 0)) * DAY_MS);
  return null;
}

function normText(s) {
  return String(s || '').toLowerCase()
    .replace(/\biii\b/g, '3').replace(/\bii\b/g, '2').replace(/\bi\b/g, '1')
    .replace(/\bsr\.?\b/g, 'senior').replace(/[^a-z0-9]+/g, '');
}

function fingerprint(company, title, city) {
  return [normText(company), normText(title), city || 'other'].join('|');
}

// ---------------------------------------------------------------------------
// Request building (one HTTP call per source per day)
// ---------------------------------------------------------------------------
function buildRequest(src, cfg) {
  const t = src.board_token;
  const base = src.endpoint_url;
  switch (src.ats) {
    case 'greenhouse':
      return { method: 'GET', url: base || `https://boards-api.greenhouse.io/v1/boards/${encodeURIComponent(t)}/jobs?content=true`, body: null };
    case 'lever':
      return { method: 'GET', url: base || `https://api.lever.co/v0/postings/${encodeURIComponent(t)}?mode=json`, body: null };
    case 'ashby':
      return { method: 'GET', url: base || `https://api.ashbyhq.com/posting-api/job-board/${encodeURIComponent(t)}?includeCompensation=true`, body: null };
    case 'smartrecruiters':
      return { method: 'GET', url: base || `https://api.smartrecruiters.com/v1/companies/${encodeURIComponent(t)}/postings?limit=100&country=in`, body: null };
    case 'workday': {
      const [host, tenant, site] = String(t).split('|');
      return {
        method: 'POST',
        url: base || `https://${host}/wday/cxs/${tenant}/${site}/jobs`,
        body: { appliedFacets: {}, limit: (cfg && cfg.workday_page_size) || 20, offset: 0, searchText: src.search_text || 'java' },
      };
    }
    default:
      throw new Error(`Unsupported ATS: ${src.ats}`);
  }
}

// ---------------------------------------------------------------------------
// Normalisation: ATS payload -> canonical job objects
// ---------------------------------------------------------------------------
function inrYearly(currency, interval, min, max) {
  const cur = String(currency || '').toUpperCase();
  const iv = String(interval || '').toLowerCase();
  const yearly = iv === '' || /year|annual|annum|1 year/.test(iv);
  if (cur !== 'INR' || !yearly) return null;
  const lo = Number(min) || null;
  const hi = Number(max) || lo;
  if (!lo && !hi) return null;
  return { min: lo || hi, max: hi || lo };
}

function baseJob(src) {
  return {
    source_id: src.id, company_id: src.company_id, company: src.company_name,
    external_id: null, title: null, location_raw: null, is_remote: false,
    department: null, employment_type: null, description_text: '',
    posted_at: null, job_url: null, apply_url: null,
    salary_currency: null, salary_interval: null, salary_min_inr: null, salary_max_inr: null,
    salary_source: 'none', salary_text: null,
  };
}

const mappers = {
  greenhouse(src, body) {
    return (body.jobs || []).map((j) => {
      const job = baseJob(src);
      const offices = (j.offices || []).map((o) => o.name).filter(Boolean);
      job.external_id = String(j.id);
      job.title = j.title;
      job.location_raw = [j.location && j.location.name, ...offices].filter(Boolean).join('; ');
      job.department = (j.departments || []).map((d) => d.name).join(', ') || null;
      job.description_text = htmlToText(j.content);
      job.posted_at = toDate(j.first_published || j.updated_at);
      job.job_url = j.absolute_url;
      job.apply_url = j.absolute_url;
      return job;
    });
  },
  lever(src, body) {
    return (Array.isArray(body) ? body : []).map((j) => {
      const job = baseJob(src);
      const c = j.categories || {};
      job.external_id = String(j.id);
      job.title = j.text;
      job.location_raw = [c.location, ...(c.allLocations || [])].filter(Boolean)
        .filter((v, i, a) => a.indexOf(v) === i).join('; ');
      job.is_remote = j.workplaceType === 'remote';
      job.department = c.team || c.department || null;
      job.employment_type = c.commitment || null;
      const lists = (j.lists || []).map((l) => `${l.text}\n${htmlToText(l.content)}`).join('\n\n');
      job.description_text = [j.descriptionPlain || htmlToText(j.description), lists, j.additionalPlain].filter(Boolean).join('\n\n');
      job.posted_at = toDate(j.createdAt);
      job.job_url = j.hostedUrl;
      job.apply_url = j.applyUrl || j.hostedUrl;
      const sr = j.salaryRange;
      if (sr) {
        job.salary_currency = sr.currency || null;
        job.salary_interval = sr.interval || null;
        const inr = inrYearly(sr.currency, sr.interval, sr.min, sr.max);
        if (inr) { job.salary_min_inr = inr.min; job.salary_max_inr = inr.max; }
        job.salary_source = 'structured';
        job.salary_text = `${sr.currency || ''} ${sr.min || ''}-${sr.max || ''} ${sr.interval || ''}`.trim();
      }
      return job;
    });
  },
  ashby(src, body) {
    return (body.jobs || []).filter((j) => j.isListed !== false).map((j) => {
      const job = baseJob(src);
      const secondary = (j.secondaryLocations || []).map((l) => l.location).filter(Boolean);
      const country = j.address && j.address.postalAddress && j.address.postalAddress.addressCountry;
      job.external_id = String(j.id);
      job.title = j.title;
      job.location_raw = [j.location, ...secondary, country].filter(Boolean).join('; ');
      job.is_remote = j.isRemote === true || j.workplaceType === 'Remote';
      job.department = j.department || j.team || null;
      job.employment_type = j.employmentType || null;
      job.description_text = j.descriptionPlain || htmlToText(j.descriptionHtml);
      job.posted_at = toDate(j.publishedAt);
      job.job_url = j.jobUrl;
      job.apply_url = j.applyUrl || j.jobUrl;
      const comp = j.compensation;
      const salary = comp && (comp.summaryComponents || []).find((s) => /salary/i.test(s.compensationType || ''));
      if (salary) {
        job.salary_currency = salary.currencyCode || null;
        job.salary_interval = salary.interval || null;
        const inr = inrYearly(salary.currencyCode, salary.interval, salary.minValue, salary.maxValue);
        if (inr) { job.salary_min_inr = inr.min; job.salary_max_inr = inr.max; }
        job.salary_source = 'structured';
        job.salary_text = comp.compensationTierSummary || null;
      }
      return job;
    });
  },
  smartrecruiters(src, body) {
    return (body.content || []).map((j) => {
      const job = baseJob(src);
      const l = j.location || {};
      const companyId = (j.company && j.company.identifier) || src.board_token;
      job.external_id = String(j.id);
      job.title = j.name;
      job.location_raw = l.fullLocation || [l.city, l.region, l.country === 'in' ? 'India' : l.country].filter(Boolean).join(', ');
      job.is_remote = l.remote === true;
      job.department = (j.department && j.department.label) || null;
      job.employment_type = (j.typeOfEmployment && j.typeOfEmployment.label) || null;
      job.description_text = ''; // list endpoint has no description; scored on title/location
      job.posted_at = toDate(j.releasedDate);
      job.job_url = `https://jobs.smartrecruiters.com/${companyId}/${j.id}`;
      job.apply_url = job.job_url;
      return job;
    });
  },
  workday(src, body, now) {
    const [host, , site] = String(src.board_token).split('|');
    return (body.jobPostings || []).filter((j) => j.externalPath).map((j) => {
      const job = baseJob(src);
      job.external_id = (j.bulletFields && j.bulletFields[0]) || j.externalPath;
      job.title = j.title;
      job.location_raw = j.locationsText || null;
      job.is_remote = /remote/i.test(j.remoteType || '');
      job.description_text = ''; // list endpoint has no description
      job.posted_at = workdayPostedOn(j.postedOn, now);
      job.job_url = `https://${host}/en-US/${site}${j.externalPath}`;
      job.apply_url = job.job_url;
      return job;
    });
  },
};

// ---------------------------------------------------------------------------
// Location, experience, salary
// ---------------------------------------------------------------------------
function anyMatch(s, patterns) {
  return (patterns || []).some((p) => new RegExp(p, 'i').test(s));
}

function matchLocation(locationRaw, isRemote, cfg) {
  const s = String(locationRaw || '').toLowerCase();
  // Pick the target city mentioned first in the string. (Key order of cfg.target_cities is not
  // reliable: Postgres jsonb re-orders object keys.)
  const cities = cfg.target_cities || {};
  let best = null;
  for (const city of Object.keys(cities)) {
    for (const alias of cities[city]) {
      const at = s.indexOf(alias);
      if (at !== -1 && (best === null || at < best.at)) best = { city, at };
    }
  }
  if (best) return { matched: best.city, reason: 'city' };
  const remote = isRemote || /\b(remote|work from home|wfh|anywhere)\b/.test(s);
  const india = anyMatch(s, cfg.india_markers);
  const foreign = anyMatch(s, cfg.foreign_markers);
  if (remote) {
    if (foreign && !india) return { matched: null, reason: 'remote_outside_india' };
    return { matched: 'remote_india', reason: india ? 'remote_india' : 'remote_unspecified' };
  }
  if (!s.trim()) return { matched: null, reason: 'location_unknown' };
  if (india) return { matched: null, reason: 'india_other_city' };
  return { matched: null, reason: 'outside_india' };
}

function extractExperience(text) {
  if (!text) return { min: null, max: null, text: null };
  const t = String(text);
  const Y = '(?:years?|yrs?)';
  const patterns = [
    { re: new RegExp(`(\\d{1,2})\\s*\\+?\\s*(?:-|–|—|to)\\s*(\\d{1,2})\\s*\\+?\\s*${Y}`, 'gi'), kind: 'range' },
    { re: new RegExp(`(?:minimum|min\\.?|at least|atleast)\\s*(?:of\\s*)?(\\d{1,2})\\s*\\+?\\s*${Y}`, 'gi'), kind: 'min' },
    { re: new RegExp(`(\\d{1,2})\\s*(?:\\+|plus)\\s*${Y}`, 'gi'), kind: 'min' },
    { re: new RegExp(`(\\d{1,2})\\s*${Y}\\s*(?:of\\s*)?(?:\\w+\\s+){0,4}(?:experience|exp\\b)`, 'gi'), kind: 'min' },
  ];
  const candidates = [];
  for (const p of patterns) {
    let m;
    while ((m = p.re.exec(t)) !== null) {
      const after = t.slice(m.index, m.index + m[0].length + 80).toLowerCase();
      const before = t.slice(Math.max(0, m.index - 60), m.index).toLowerCase();
      const ctx = /experience|exp\b|work(ed|ing)? (in|on|with)|background|industry/.test(after + ' ' + before);
      const min = Number(m[1]);
      const max = p.kind === 'range' ? Number(m[2]) : null;
      if (min > 25 || (max !== null && (max > 30 || max < min))) continue;
      candidates.push({ index: m.index, min, max, text: m[0].trim(), ctx });
    }
  }
  if (!candidates.length) return { min: null, max: null, text: null };
  const withCtx = candidates.filter((c) => c.ctx).sort((a, b) => a.index - b.index);
  const pick = withCtx[0] || candidates.sort((a, b) => a.index - b.index)[0];
  return { min: pick.min, max: pick.max, text: pick.text };
}

function classifyExperience(min, max) {
  if (min === null && max === null) return { fit: 'unknown', in2to5: null };
  const lo = min === null ? 0 : min;
  const in2to5 = lo <= 5 && (max === null ? true : max >= 2);
  if (lo >= 6) return { fit: 'over', in2to5 };
  if (max !== null && max < 2) return { fit: 'under', in2to5 };
  if (lo === 5) return { fit: 'stretch', in2to5 };
  return { fit: 'fit', in2to5 };
}

// Only reads salary that is literally written in the JD. Requires an explicit unit
// (LPA / lakh) or a rupee marker with full numbers, so "3-5 years" never matches.
function extractSalaryFromText(text) {
  if (!text) return null;
  const t = String(text);
  let m = t.match(/(?:₹|rs\.?|inr)?\s*(\d{1,3}(?:\.\d+)?)\s*(?:-|–|—|to)\s*(?:₹|rs\.?|inr)?\s*(\d{1,3}(?:\.\d+)?)\s*(?:lpa|lakhs?|lacs?|l\.?p\.?a\.?)\b/i);
  if (m) return { min: Math.round(Number(m[1]) * LAKH), max: Math.round(Number(m[2]) * LAKH), text: m[0].trim() };
  m = t.match(/(?:₹|rs\.?|inr)\s*(\d{1,3}(?:,\d{2,3})+)\s*(?:-|–|—|to)\s*(?:₹|rs\.?|inr)?\s*(\d{1,3}(?:,\d{2,3})+)/i);
  if (m) {
    const lo = Number(m[1].replace(/,/g, '')); const hi = Number(m[2].replace(/,/g, ''));
    if (lo >= LAKH && hi >= lo) return { min: lo, max: hi, text: m[0].trim() };
  }
  m = t.match(/(?:ctc|salary|compensation|package)[^.\n]{0,30}?(\d{1,3}(?:\.\d+)?)\s*(?:lpa|lakhs? per annum)\b/i);
  if (m) return { min: Math.round(Number(m[1]) * LAKH), max: Math.round(Number(m[1]) * LAKH), text: m[0].trim() };
  return null;
}

function compFit(job, targetLpa) {
  const lo = (targetLpa && targetLpa.min) || 15;
  if (job.salary_min_inr || job.salary_max_inr) {
    const min = job.salary_min_inr || job.salary_max_inr;
    const max = job.salary_max_inr || job.salary_min_inr;
    if (min >= ((targetLpa && targetLpa.max) || 20) * LAKH) return 'above';
    if (max >= lo * LAKH) return 'fit';
    return 'below';
  }
  if (job.salary_source !== 'none' && job.salary_currency && String(job.salary_currency).toUpperCase() !== 'INR') return 'non_inr';
  return 'unknown';
}

// Deterministic: Claude never decides compensation points, so salary can't be invented.
function compensationPoints(job) {
  switch (job.comp_fit) {
    case 'fit': case 'above': return 10;
    case 'below': return (job.salary_max_inr || 0) >= 12 * LAKH ? 4 : 0;
    default: return 5; // unknown / non-INR: neutral, flagged in the report
  }
}

// ---------------------------------------------------------------------------
// Pre-filter (cheap, deterministic; only survivors are stored with descriptions and scored)
// ---------------------------------------------------------------------------
function prefilter(job, cfg, now) {
  const title = String(job.title || '');
  if (!new RegExp(cfg.title_include_regex, 'i').test(title)) return 'title_not_relevant';
  if (new RegExp(cfg.title_exclude_regex, 'i').test(title)) return 'title_excluded';
  if (!job.matched_location && job.location_reason !== 'location_unknown') return job.location_reason;
  if (job.posted_at && now.getTime() - new Date(job.posted_at).getTime() > (cfg.max_job_age_days || 30) * DAY_MS) return 'stale';
  if (job.exp_fit === 'over') return 'experience_over';
  if (job.exp_fit === 'under') return 'experience_under';
  return null;
}

function enrichAndFilter(job, cfg, profile, now) {
  const loc = matchLocation(job.location_raw, job.is_remote, cfg);
  job.matched_location = loc.matched;
  job.location_reason = loc.reason;
  if (loc.matched === 'remote_india') job.is_remote = true;

  const exp = extractExperience(job.description_text);
  const cls = classifyExperience(exp.min, exp.max);
  job.exp_min = exp.min; job.exp_max = exp.max; job.exp_text = exp.text;
  job.exp_fit = cls.fit; job.exp_in_2_5 = cls.in2to5;

  if (job.salary_source === 'none') {
    const s = extractSalaryFromText(job.description_text);
    if (s) { job.salary_min_inr = s.min; job.salary_max_inr = s.max; job.salary_text = s.text; job.salary_source = 'jd_text'; job.salary_currency = 'INR'; }
  }
  job.comp_fit = compFit(job, profile && profile.target_ctc_lpa);
  job.fingerprint = fingerprint(job.company, job.title, job.matched_location);

  const reason = prefilter(job, cfg, now);
  job.prefilter_pass = reason === null;
  job.prefilter_reason = reason || 'ok';
  if (!job.prefilter_pass) job.description_text = null; // don't store text we will never use
  else if (job.description_text && job.description_text.length > (cfg.max_description_chars || 24000)) {
    job.description_truncated = true;
    job.description_text = job.description_text.slice(0, cfg.max_description_chars || 24000);
  }
  job.posted_at = job.posted_at ? new Date(job.posted_at).toISOString() : null;
  delete job.location_reason;
  return job;
}

// Turn one HTTP result into { result, jobs }.
function processSourceResponse(src, response, cfg, profile, now) {
  const status = response && (response.statusCode || (response.error ? 0 : 200));
  const result = { source_id: src.id, ok: false, status: status || 0, fetched: 0, passed: 0, error: null };
  if (!response || response.error) {
    result.error = String((response && response.error && (response.error.message || response.error)) || 'no response').slice(0, 500);
    return { result, jobs: [] };
  }
  if (status < 200 || status >= 300) {
    result.error = `HTTP ${status}`;
    return { result, jobs: [] };
  }
  let body = response.body;
  if (typeof body === 'string') {
    try { body = JSON.parse(body); } catch (e) { result.error = 'invalid JSON body'; return { result, jobs: [] }; }
  }
  let jobs;
  try {
    jobs = mappers[src.ats](src, body || {}, now);
  } catch (e) {
    result.error = `parse error: ${e.message}`;
    return { result, jobs: [] };
  }
  const seen = new Set();
  jobs = jobs.filter((j) => j.external_id && j.title && j.job_url && !seen.has(j.external_id) && seen.add(j.external_id))
    .map((j) => enrichAndFilter(j, cfg, profile, now));
  result.ok = true;
  // complete=false means the board returned only one page: absence is not proof of expiry.
  if (src.ats === 'smartrecruiters') result.complete = !(body && body.totalFound > (body.content || []).length);
  else if (src.ats === 'workday') result.complete = !(body && body.total > (body.jobPostings || []).length);
  else result.complete = true;
  result.fetched = jobs.length;
  result.passed = jobs.filter((j) => j.prefilter_pass).length;
  return { result, jobs };
}

// ---------------------------------------------------------------------------
// Claude scoring
// ---------------------------------------------------------------------------
const SCORE_SCHEMA = {
  type: 'object',
  properties: {
    technical_score: { type: 'integer', description: '0-40' },
    experience_score: { type: 'integer', description: '0-20' },
    location_score: { type: 'integer', description: '0-15' },
    company_score: { type: 'integer', description: '0-15' },
    company_type: { type: 'string', enum: ['product', 'fintech', 'saas_cloud', 'engineering_led', 'services_consulting', 'unknown'] },
    experience_required: { anyOf: [{ type: 'string' }, { type: 'null' }], description: 'Verbatim experience requirement from the JD, or null' },
    salary_quote: { anyOf: [{ type: 'string' }, { type: 'null' }], description: 'Verbatim salary/CTC text copied from the JD, or null if none is written' },
    matched_skills: { type: 'array', items: { type: 'string' } },
    missing_skills: { type: 'array', items: { type: 'string' } },
    red_flags: { type: 'array', items: { type: 'string' } },
    verdict: { type: 'string', enum: ['strong_match', 'good_match', 'weak_match', 'not_relevant'] },
    summary: { type: 'string', description: 'One or two plain sentences, max 40 words' },
  },
  required: ['technical_score', 'experience_score', 'location_score', 'company_score', 'company_type', 'experience_required',
    'salary_quote', 'matched_skills', 'missing_skills', 'red_flags', 'verdict', 'summary'],
  additionalProperties: false,
};

function buildSystemPrompt(profile) {
  return `You screen job postings for one specific software engineer and score how well each posting fits them. Your scores decide which jobs they spend their limited application and referral effort on, so be calibrated and honest: a mediocre fit must not look good.

<candidate_profile>
${JSON.stringify(profile, null, 2)}
</candidate_profile>

Score four components. Compensation is scored separately by code from verified salary data, so do not score it.

1. technical_score (0-40): overlap between the role's actual day-to-day stack and the candidate's skills.
   - 34-40: Java/Spring Boot backend, microservices, Kafka/async/event-driven or distributed systems work is the core of the role.
   - 25-33: backend role where Java is one accepted language, or JVM-adjacent (Kotlin/Scala) with strong distributed-systems overlap.
   - 15-24: backend role centred on another language (Go, Python, Node, C++) where the candidate's distributed-systems experience still transfers.
   - 0-14: frontend, mobile, data science, QA, DevOps-only, or a stack with little overlap.
2. experience_score (0-20): the candidate has about 3 years and is an SDE-1 targeting SDE-1/SDE-2.
   - 17-20: requirement range includes 3 years (e.g. 2-5, 3+, 1-4) or SDE-2 / Software Engineer II level.
   - 10-16: requirement is 4-5 years minimum, or the level is ambiguous ("Senior" at a company where that means 3-6 years).
   - 0-9: 6+ years, staff/principal/lead scope, or fresher/intern level.
   - If no requirement is stated, infer the level from title and responsibilities and score 12-16.
3. location_score (0-15): preferred locations are Mumbai, Bengaluru, Hyderabad, Pune and remote within India.
   - 15: one of those cities or remote open to India. 8: hybrid with a preferred city among several options. 0-5: elsewhere or remote restricted to other countries.
4. company_score (0-15): preference order is product companies, fintech, cloud/SaaS, then strong engineering-led tech companies.
   - 13-15: product, fintech or cloud/SaaS company that builds its own platform. 9-12: engineering-led tech company. 3-8: IT services, consulting, staffing or captive back-office. Use the company category provided unless the posting clearly contradicts it.

Hard rules:
- Never invent facts. Use only the posting text and fields given. If the description is missing, say so in red_flags, score technical from the title alone (max 28) and give verdict weak_match or good_match at most.
- salary_quote: copy salary or CTC text exactly as written in the posting. If no salary is written, return null. Never estimate or guess compensation.
- experience_required: copy the requirement exactly as written, or null.
- matched_skills / missing_skills: skills the posting asks for that the candidate has / does not have (max 8 each, short names).
- red_flags: concrete concerns only (e.g. "requires 7+ years", "on-call heavy", "contract role", "no description"). Empty array if none.
- verdict: strong_match (clearly worth applying and seeking a referral), good_match, weak_match, not_relevant.
- summary: plain, specific language; no hype.`;
}

function buildUserMessage(job) {
  const fields = {
    company: job.company, company_category: job.company_category || 'unknown', title: job.title,
    location: job.location_raw, matched_location: job.matched_location, remote: job.is_remote,
    department: job.department, employment_type: job.employment_type,
    posted_at: job.posted_at, experience_extracted_by_regex: job.exp_text,
    structured_salary: job.salary_source === 'structured' ? job.salary_text : null,
  };
  const desc = job.description_text ? job.description_text : '(no description available from this job board)';
  return `<job_fields>\n${JSON.stringify(fields, null, 2)}\n</job_fields>\n\n<job_description${job.description_truncated ? ' truncated="true"' : ''}>\n${desc}\n</job_description>\n\nScore this posting for the candidate.`;
}

function supportsServerFallback(model) {
  return /^claude-(opus-5|fable-5|sonnet-5-5)/.test(model);
}

function buildClaudeRequest(job, profile, cfg) {
  const model = cfg.scoring_model || 'claude-opus-5-5';
  const body = {
    model,
    max_tokens: 8000,
    system: [{ type: 'text', text: buildSystemPrompt(profile), cache_control: { type: 'ephemeral' } }],
    messages: [{ role: 'user', content: buildUserMessage(job) }],
    output_config: { effort: cfg.scoring_effort || 'low', format: { type: 'json_schema', schema: SCORE_SCHEMA } },
  };
  const headers = { 'anthropic-version': '2026-10-21' };
  if (cfg.use_fallbacks !== false && supportsServerFallback(model)) {
    body.fallbacks = 'default';
    headers['anthropic-beta'] = 'server-side-fallback-2026-07-01';
  }
  return { url: `${(cfg.anthropic_base_url || 'https://api.anthropic.com').replace(/\/$/, '')}/v1/messages`, headers, body };
}

function clampInt(v, lo, hi) {
  const n = Math.round(Number(v));
  if (!Number.isFinite(n)) return lo;
  return Math.max(lo, Math.min(hi, n));
}

function squash(s) { return String(s || '').replace(/\s+/g, ' ').trim().toLowerCase(); }

// Returns { ok:true, score, job_update } or { ok:false, error }.
function parseClaudeResponse(resp, job, cfg) {
  if (!resp) return { ok: false, error: 'empty response' };
  if (resp.error) return { ok: false, error: String(resp.error.message || JSON.stringify(resp.error)).slice(0, 500) };
  if (resp.stop_reason === 'refusal') return { ok: false, error: `refusal: ${(resp.stop_details && resp.stop_details.category) || 'unknown'}` };
  if (resp.stop_reason === 'max_tokens') return { ok: false, error: 'max_tokens reached' };
  const textBlock = (resp.content || []).filter((b) => b.type === 'text').map((b) => b.text).join('');
  let out;
  try { out = JSON.parse(textBlock); } catch (e) { return { ok: false, error: `invalid JSON from model: ${textBlock.slice(0, 200)}` }; }

  // Salary from Claude is accepted only if the quote really appears in the JD.
  const job_update = {};
  let effectiveJob = job;
  if (out.salary_quote && job.salary_source === 'none' && job.description_text &&
      squash(job.description_text).includes(squash(out.salary_quote))) {
    const parsed = extractSalaryFromText(out.salary_quote);
    job_update.salary_text = String(out.salary_quote).slice(0, 300);
    job_update.salary_source = 'jd_text';
    if (parsed) {
      job_update.salary_min_inr = parsed.min; job_update.salary_max_inr = parsed.max;
      effectiveJob = Object.assign({}, job, { salary_min_inr: parsed.min, salary_max_inr: parsed.max, salary_source: 'jd_text', salary_currency: 'INR' });
      job_update.comp_fit = compFit(effectiveJob, cfg.target_ctc_lpa);
      effectiveJob.comp_fit = job_update.comp_fit;
    }
  }
  const s = {
    technical: clampInt(out.technical_score, 0, 40),
    experience: clampInt(out.experience_score, 0, 20),
    location: clampInt(out.location_score, 0, 15),
    company: clampInt(out.company_score, 0, 15),
    compensation: compensationPoints(effectiveJob),
  };
  let verdict = out.verdict;
  if (!job.description_text) {               // title-only postings can't be a confident match
    s.technical = Math.min(s.technical, 28);
    if (verdict === 'strong_match') verdict = 'good_match';
  }
  const usage = resp.usage || {};
  return {
    ok: true,
    job_update,
    score: Object.assign(s, {
      job_id: job.id,
      total: clampInt(s.technical + s.experience + s.location + s.company + s.compensation, 0, 100),
      verdict,
      company_type: out.company_type,
      matched_skills: (out.matched_skills || []).slice(0, 8),
      missing_skills: (out.missing_skills || []).slice(0, 8),
      red_flags: (out.red_flags || []).slice(0, 8),
      summary: String(out.summary || '').slice(0, 400),
      model: resp.model || cfg.scoring_model,
      prompt_version: cfg.prompt_version || 'score-v1',
      raw_response: out,
      input_tokens: usage.input_tokens || null,
      output_tokens: usage.output_tokens || null,
      cache_read_tokens: usage.cache_read_input_tokens || null,
    }),
  };
}

const DiscoveryLib = {
  decodeEntities, htmlToText, toDate, workdayPostedOn, normText, fingerprint, buildRequest, mappers,
  matchLocation, extractExperience, classifyExperience, extractSalaryFromText, compFit, compensationPoints,
  prefilter, enrichAndFilter, processSourceResponse, SCORE_SCHEMA, buildSystemPrompt, buildUserMessage,
  buildClaudeRequest, parseClaudeResponse, clampInt, inrYearly,
};
if (typeof module !== 'undefined' && module.exports) module.exports = DiscoveryLib;
