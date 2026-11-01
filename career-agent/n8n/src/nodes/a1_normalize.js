// Code node "Normalize & filter" (Run once for all items). Input: one HTTP result per source.
const { cfg, profile } = $('Load config').first().json;
const run = $('Start run').first().json;
const requests = $('Build requests').all();
const now = new Date();

function pairedIndex(item, i) {
  const p = item.pairedItem;
  if (typeof p === 'number') return p;
  if (Array.isArray(p) && p.length) return p[0].item;
  if (p && typeof p.item === 'number') return p.item;
  return i;
}

const jobs = [];
const results = [];
$input.all().forEach((item, i) => {
  const req = requests[pairedIndex(item, i)] || requests[i];
  const src = req.json.source;
  const resp = item.json.error ? { error: item.json.error } : item.json; // fullResponse: {statusCode, body, headers}
  const out = LIB.processSourceResponse(src, resp, cfg, profile, now);
  out.result.company = src.company_name;
  out.result.ats = src.ats;
  results.push(out.result);
  jobs.push(...out.jobs);
});

return [{
  json: {
    run_id: run.run_id,
    max_failures: cfg.max_source_failures || 5,
    jobs_json: JSON.stringify(jobs),
    results_json: JSON.stringify(results),
    stats: {
      sources: results.length,
      sources_failed: results.filter((r) => !r.ok).length,
      fetched: jobs.length,
      passed_prefilter: jobs.filter((j) => j.prefilter_pass).length,
    },
  },
}];
