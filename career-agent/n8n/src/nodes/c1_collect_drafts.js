// Code node "Collect draft results": pair drafts.create responses with draft rows.
const drafts = $('Expand drafts').all();
function pairedIndex(item, i) {
  const p = item.pairedItem;
  if (typeof p === 'number') return p;
  if (Array.isArray(p) && p.length) return p[0].item;
  if (p && typeof p.item === 'number') return p.item;
  return i;
}
const results = $input.all().map((item, i) => {
  const d = (drafts[pairedIndex(item, i)] || drafts[i]).json;
  const r = item.json;
  if (r.error || !r.id) return { id: d.id, ok: false, gmail_draft_id: null, error: String((r.error && (r.error.message || JSON.stringify(r.error))) || 'no draft id').slice(0, 500) };
  return { id: d.id, ok: true, gmail_draft_id: r.id, error: null };
});
return [{ json: { results_json: JSON.stringify(results) } }];
