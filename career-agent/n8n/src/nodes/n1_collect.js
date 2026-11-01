// Code node "Collect results": pair Telegram responses with notification ids.
const sent = $('Format messages').all();
function pairedIndex(item, i) {
  const p = item.pairedItem;
  if (typeof p === 'number') return p;
  if (Array.isArray(p) && p.length) return p[0].item;
  if (p && typeof p.item === 'number') return p.item;
  return i;
}
const results = $input.all().map((item, i) => {
  const n = (sent[pairedIndex(item, i)] || sent[i]).json;
  const err = item.json.error;
  return { id: n.id, ok: !err, error: err ? String(err.message || JSON.stringify(err)).slice(0, 500) : null };
});
return [{ json: { results_json: JSON.stringify(results) } }];
