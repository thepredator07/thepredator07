#!/usr/bin/env bash
# Creates the n8n credentials from .env and imports the workflows, with fixed IDs so the
# workflows bind to their credentials without clicking. Re-runnable (import overwrites by ID).
#   scripts/bootstrap.sh            # import, leave workflows unpublished
#   scripts/bootstrap.sh --publish  # also publish A1 so the 07:00 schedule runs
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
: "${ANTHROPIC_API_KEY:?set ANTHROPIC_API_KEY in .env}" "${CAREER_DB_PASSWORD:?}"

# Build the credentials file inside the n8n container (no Node needed on the host); the secrets
# travel over stdin, never on a command line, and the file is deleted right after import.
docker compose exec -T n8n sh -c 'cat > /tmp/career.env && node -e "
const env = Object.fromEntries(require(\"fs\").readFileSync(\"/tmp/career.env\", \"utf8\").split(\"\\n\")
  .map((l) => l.match(/^([A-Z0-9_]+)=(.*)$/)).filter(Boolean).map((m) => [m[1], m[2].replace(/\\s+#.*$/, \"\").trim()]));
const c = [
  { id: \"careerPostgres01\", name: \"Career Postgres\", type: \"postgres\",
    data: { host: \"postgres\", port: 5432, database: \"career\", user: \"career_app\", password: env.CAREER_DB_PASSWORD, ssl: \"disable\" } },
  { id: \"anthropicApiKey1\", name: \"Anthropic API key\", type: \"httpHeaderAuth\",
    data: { name: \"x-api-key\", value: env.ANTHROPIC_API_KEY } },
];
if (env.TELEGRAM_BOT_TOKEN) c.push({ id: \"telegramBotApi01\", name: \"Telegram bot\", type: \"telegramApi\", data: { accessToken: env.TELEGRAM_BOT_TOKEN } });
require(\"fs\").writeFileSync(\"/tmp/creds.json\", JSON.stringify(c));
" && n8n import:credentials --input=/tmp/creds.json; rc=$?; rm -f /tmp/creds.json /tmp/career.env; exit $rc' < .env

docker compose exec -T n8n n8n import:workflow --input=/workflows/Z0_error_handler.json
docker compose exec -T n8n n8n import:workflow --input=/workflows/A1_job_discovery.json

if [[ -n "${TELEGRAM_CHAT_ID:-}" ]]; then
  docker compose exec -T postgres psql -U "$POSTGRES_ADMIN_USER" -d career -c \
    "UPDATE settings SET value = value || jsonb_build_object('telegram_chat_id', '${TELEGRAM_CHAT_ID}') WHERE key = 'notifications'"
fi

if [[ "${1:-}" == "--publish" ]]; then
  docker compose exec -T n8n n8n publish:workflow --id=careerZ0ErrorHnd
  docker compose exec -T n8n n8n publish:workflow --id=careerA1Discover
  docker compose restart n8n
fi
echo "Done. Open http://localhost:5678, run 'A1 Job Discovery' once by hand, then publish it."
