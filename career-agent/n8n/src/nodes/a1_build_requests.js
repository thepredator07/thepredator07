// Code node "Build requests" (Run once for all items). Input: one row from "Load config".
const { cfg, sources } = $input.first().json;
if (!cfg) throw new Error("settings row 'discovery' is missing: run db/002_seed.sql");
if (!sources || !sources.length) throw new Error('No active job sources (job_sources.is_active = true)');
return sources.map((source) => {
  const r = LIB.buildRequest(source, cfg);
  return {
    json: {
      source,
      method: r.method,
      url: r.url,
      send_body: r.method === 'POST',
      body_json: JSON.stringify(r.body || {}),
      headers_json: JSON.stringify({
        Accept: 'application/json',
        'User-Agent': 'personal-career-agent/1.0 (single-user job search; one request per board per day)',
      }),
    },
  };
});
