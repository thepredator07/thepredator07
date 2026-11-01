// Code node "Parse scores" (Run once for all items). Input: {job, response} per scored job,
// collected by the "One job at a time" loop.
const { cfg, profile } = $('Load config').first().json;
const scoringCfg = Object.assign({}, cfg, { target_ctc_lpa: profile.target_ctc_lpa });

const scores = [];
const updates = [];
const failures = [];
for (const item of $input.all()) {
  const { job, response } = item.json;
  if (!job) continue;
  const parsed = LIB.parseClaudeResponse(response, job, scoringCfg);
  if (!parsed.ok) { failures.push({ job_id: job.id, error: parsed.error }); continue; }
  scores.push(parsed.score);
  if (Object.keys(parsed.job_update).length) updates.push(Object.assign({ job_id: job.id }, parsed.job_update));
}

return [{
  json: {
    scores_json: JSON.stringify(scores),
    updates_json: JSON.stringify(updates),
    failures_json: JSON.stringify(failures),
    scored: scores.length,
    failed: failures.length,
  },
}];
