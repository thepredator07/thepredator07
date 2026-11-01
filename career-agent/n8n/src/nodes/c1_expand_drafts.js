// Code node "Expand drafts": one item per claimed draft ({success:true} when none).
const { cfg } = $('Load config').first().json;
const url = `${cfg.gmail_base_url.replace(/\/$/, '')}/gmail/v1/users/me/drafts`;
return $input.all().filter((it) => it.json.id !== undefined).map((it) => ({
  json: { id: it.json.id, url, body_json: JSON.stringify({ message: { raw: it.json.raw, threadId: it.json.gmail_thread_id } }) },
}));
