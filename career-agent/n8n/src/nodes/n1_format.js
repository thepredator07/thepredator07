// Code node "Format messages": claimed notifications -> Telegram text.
const icon = { 1: '🔴', 2: '🟡', 3: '⚪' };
return $input.all().filter((it) => it.json.id !== undefined).map((it) => ({
  json: { id: it.json.id, chat_id: it.json.chat_id, text: `${icon[it.json.priority] || ''} ${it.json.title}${it.json.body ? `\n\n${it.json.body}` : ''}`.slice(0, 4000) },
}));
