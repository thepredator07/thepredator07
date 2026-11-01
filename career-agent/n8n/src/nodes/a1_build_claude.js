// Code node "Build Claude requests" (Run once for all items). Input: jobs to score.
const { cfg, profile } = $('Load config').first().json;
return $input.all().filter((it) => it.json.id !== undefined).map((it) => {
  const job = it.json;
  const r = LIB.buildClaudeRequest(job, profile, cfg);
  return { json: { job, url: r.url, headers_json: JSON.stringify(r.headers), body_json: JSON.stringify(r.body) } };
});
