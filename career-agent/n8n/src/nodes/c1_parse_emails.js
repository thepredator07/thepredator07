// Code node "Parse emails" (Run once for all items). Input: Gmail format=full responses.
const { cfg } = $('Load config').first().json;
const cands = $('Expand candidates').all();
function pairedIndex(item, i) {
  const p = item.pairedItem;
  if (typeof p === 'number') return p;
  if (Array.isArray(p) && p.length) return p[0].item;
  if (p && typeof p.item === 'number') return p.item;
  return i;
}
const emails = [];
$input.all().forEach((item, i) => {
  if (item.json.error || !item.json.payload) return;             // retried next run
  const cand = (cands[pairedIndex(item, i)] || cands[i]).json;
  const email = LIB.parseFullMessage(item.json, cfg.max_body_chars);
  email.triage_reason = cand.triage_reason;
  emails.push(email);
});
if (!emails.length) return [];
return [{ json: { emails, thread_ids_json: JSON.stringify([...new Set(emails.map((e) => e.thread_id))]) } }];
