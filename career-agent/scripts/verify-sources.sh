#!/usr/bin/env bash
# One request per job source to check the seeded ATS board tokens. Run from your own machine
# (or the server). Prints status and job count; deactivate wrong ones or fix the token.
#   scripts/verify-sources.sh            # print report
#   scripts/verify-sources.sh --apply    # also mark 404 sources inactive in the DB
# Note: the IDs are read via psql; this loops over stdin, so psql must not read the terminal.
set -uo pipefail
cd "$(dirname "$0")/.."
set -a; source .env; set +a
PSQL=(docker compose exec -T postgres psql -U "$POSTGRES_ADMIN_USER" -d career -X -q -t -A -F '|')
"${PSQL[@]}" -c "SELECT s.id, c.name, s.ats, s.board_token, COALESCE(s.endpoint_url,'') FROM job_sources s JOIN companies c ON c.id=s.company_id ORDER BY s.id" |
while IFS='|' read -r id company ats token endpoint; do
  case "$ats" in
    greenhouse) url="https://boards-api.greenhouse.io/v1/boards/$token/jobs"; method=GET ;;
    lever) url="https://api.lever.co/v0/postings/$token?mode=json&limit=200"; method=GET ;;
    ashby) url="https://api.ashbyhq.com/posting-api/job-board/$token"; method=GET ;;
    smartrecruiters) url="https://api.smartrecruiters.com/v1/companies/$token/postings?limit=1"; method=GET ;;
    workday) host="${token%%|*}"; rest="${token#*|}"; tenant="${rest%%|*}"; site="${rest#*|}"
             url="https://$host/wday/cxs/$tenant/$site/jobs"; method=POST ;;
  esac
  [[ -n "$endpoint" ]] && url="$endpoint"
  if [[ $method == POST ]]; then
    out=$(curl -s -m 20 -w '\n%{http_code}' -H 'Content-Type: application/json' -d '{"appliedFacets":{},"limit":1,"offset":0,"searchText":""}' "$url")
  else
    out=$(curl -s -m 20 -w '\n%{http_code}' "$url")
  fi
  code=$(tail -n1 <<<"$out"); body=$(sed '$d' <<<"$out")
  count=$(docker compose exec -T n8n node -e 'let b;try{b=JSON.parse(require("fs").readFileSync(0,"utf8"))}catch{console.log("-");process.exit()}
    console.log(Array.isArray(b)?b.length:(b.jobs||b.content||b.jobPostings||[]).length + (b.total?` of ${b.total}`:b.totalFound?` of ${b.totalFound}`:""))' <<<"$body")
  printf '%-4s %-22s %-16s %-4s jobs=%s\n' "$id" "$company" "$ats" "$code" "$count"
  if [[ "${1:-}" == "--apply" && "$code" == "404" ]]; then "${PSQL[@]}" -c "UPDATE job_sources SET is_active=false, last_status=404 WHERE id=$id"; fi
  sleep 1
done
