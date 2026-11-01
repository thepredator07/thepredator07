// Code node "Triage" (Run once for all items). Input: one Gmail metadata response per new message.
const { cfg, tracked_threads } = $('Load config').first().json;
const skips = [];
const candidates = [];
for (const item of $input.all()) {
  const msg = item.json;
  if (msg.error || !msg.id || !msg.payload) continue;            // fetch failed: retried next run
  const meta = LIB.parseMetadata(msg);
  const t = LIB.triage(meta, cfg, tracked_threads || []);
  if (t.relevance === 'recruiter') candidates.push({ id: meta.id, thread_id: meta.thread_id, triage_reason: t.reason });
  else skips.push({
    gmail_message_id: meta.id, gmail_thread_id: meta.thread_id, received_at: meta.received_at,
    from_email: meta.from_email, from_name: meta.from_name,
    subject: t.relevance === 'job_alert' ? meta.subject : null,   // keep nothing personal from unrelated mail
    relevance: t.relevance, triage_reason: t.reason,
  });
}
const limit = cfg.max_messages_per_run || 25;                      // the rest wait for the next run
return [{ json: {
  skips_json: JSON.stringify(skips),
  candidates: candidates.slice(0, limit),
  deferred: Math.max(0, candidates.length - limit),
} }];
