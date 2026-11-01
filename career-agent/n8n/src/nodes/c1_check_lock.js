// Code node "Check lock" (Run once for all items). Stops quietly if another run holds the lease.
const { got_lock, cfg } = $input.first().json;
if (!got_lock) return [];
if (!cfg) throw new Error("settings row 'gmail' is missing: run db/003_module_c.sql");
const url = `${cfg.gmail_base_url.replace(/\/$/, '')}/gmail/v1/users/me/messages?maxResults=${cfg.max_list || 100}&q=${encodeURIComponent(cfg.query)}`;
return [{ json: { url } }];
