#!/usr/bin/env bash
# Apply every db/0NN_*.sql to an existing install (all files are idempotent), then re-grant the
# workflow role on any new tables.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
for f in db/0*.sql; do
  echo "applying $f"
  docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_ADMIN_USER" -d career < "$f"
done
docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -q -U "$POSTGRES_ADMIN_USER" -d career <<'SQL'
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO career_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO career_app;
SQL
