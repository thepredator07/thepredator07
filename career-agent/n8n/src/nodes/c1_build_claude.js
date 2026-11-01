// Code node "Build Claude requests". Input: thread context rows (may be a single empty item).
const { cfg, profile, anthropic_base_url } = $('Load config').first().json;
const emails = $('Parse emails').first().json.emails;
const ctx = {};
for (const it of $input.all()) if (it.json.gmail_thread_id) ctx[it.json.gmail_thread_id] = it.json;
return emails.map((email) => {
  const c = ctx[email.thread_id];
  const threadContext = c ? { category: c.category, summary: c.summary, company: c.company, role: c.role, application_status: c.application_status } : null;
  const r = LIB.buildEmailClaudeRequest(email, threadContext, profile, cfg, anthropic_base_url);
  return { json: { email, url: r.url, headers_json: JSON.stringify(r.headers), body_json: JSON.stringify(r.body) } };
});
