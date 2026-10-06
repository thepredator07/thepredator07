# Morning check (5 minutes)

## 1. Start it (about 1 minute)

```bash
git fetch origin ccr-3825fb03-yd2xgl && git checkout ccr-3825fb03-yd2xgl
cd software-factory
cp .env.example .env
docker compose up --build
```

Wait for `Started FactoryApplication` in the logs, then give the live demo tickets about 20 seconds to finish.

> If `docker compose up --build` fails while building the image (it couldn't be fully checked in my sandbox, see
> DECISIONS #20), use the no-Docker-build path instead:
> `docker run -d --name factory-pg -e POSTGRES_DB=factory -e POSTGRES_USER=factory -e POSTGRES_PASSWORD=localdev -p 5432:5432 postgres:16-alpine`
> then `SPRING_PROFILES_ACTIVE=demo DATABASE_PASSWORD=localdev mvn spring-boot:run`.

## 2. Look at the pages (about 3 minutes)

**<http://localhost:8080/tickets>** (`/` redirects here)
- Four tiles: **Tickets 23**, **Success rate 75.0%** (15 done, 5 failed), **Avg duration** (about 28m; seeded history
  is backdated), **Total cost** (about $11.56).
- A row of state filters, then a table: 15 DONE, 5 FAILED, 2 CANCELLED, 1 AWAITING_APPROVAL. Click **FAILED** to
  filter the table.
- The four newest rows (issues #900–903) are the live tickets that just went through the pipeline.
  These numbers come from my run; the seeded history uses a fixed random seed, so yours should match.

**Click "Fix rounding in tax calculation"** (a live ticket, checks fail once)
- State history: RECEIVED → SANDBOX_READY → CODING → CHECKS → **CODING** ("Checks failed, sending output back to
  the agent (retry 1)") → CHECKS → PR_OPENED → AWAITING_APPROVAL → DONE, each with a timestamp and time spent.
- Usage: 12 turns, $0.24, 1 retry. Branch `factory/<id>`, a PR link, and a "Last checks output" box with the
  simulated test failure.

**Click "Add audit log for admin actions"** (AWAITING_APPROVAL)
- Has **Approve PR (fake GitHub)** and **Cancel ticket** buttons. Click Approve: within about 3 seconds a refresh
  shows DONE, with "PR #… approved" as the last history line.

**Click "Port the whole frontend to a new framework"** (FAILED)
- Failure: `guardrail: cost $3.75 exceeded limit $2.00`. Agent ran once, no retries.

**<http://localhost:8080/stats>**
- Tiles: success rate, avg duration, avg cost, total cost, tokens, retries.
- Tickets by state with share bars, cost by outcome (DONE vs FAILED), the configured guardrails ($2.00, 40 turns,
  30m, 3 retries), and recent failures with reasons.

**Click "Fix flaky OrderSyncIntegrationTest" (attempt 2)** (since M1)
- The issue failed on attempt 1 and was fixed on attempt 2. The page shows "Attempt 2 of 2" and an
  "All attempts at this issue" table linking both.

## 3. Send your own issue through (about 1 minute)

```bash
curl -X POST localhost:8080/api/fake/issues -H 'Content-Type: application/json' \
  -d '{"number": 501, "title": "Add a /version endpoint", "body": "Return the git SHA."}'
```

Refresh `/tickets` within about 5 seconds: a new ticket, already DONE, with a PR. Add
`"body": "fake-agent: fail"` to watch one fail after 3 retries, or `"fake-approval: pending"` to get one waiting on
the Approve button. All the directives are listed in README.md.

Since M1 you can also re-trigger and close issues on the fake GitHub:

```bash
curl -X POST localhost:8080/api/fake/issues/903/relabel   # failed issue: the next poll starts attempt 2
curl -X POST localhost:8080/api/fake/issues/501/close     # a running attempt without a PR yet is cancelled
```

## 4. Run the tests yourself

```bash
cd software-factory && mvn clean verify      # needs Docker running (Testcontainers)
```

Expect `Tests run: 326, Failures: 0, Errors: 0, Skipped: 0` and `BUILD SUCCESS` (about a minute after
dependencies are downloaded).

---

## Results from the overnight run (Phase 1)

> This section records the original Phase 1 run (164 tests, 22 demo tickets). Later milestones are verified in their
> PRs and in `docs/PHASE2-PLAN.md`.

All commands below were actually run in the build sandbox (Java 21.0.11, Maven 3.9.11, Docker 29.6.2,
PostgreSQL 16 via Testcontainers).

### Test suite: `mvn clean verify` from a fresh clone: 164 passed, 0 failed, 0 skipped

| Test class | What it proves | Tests | Result |
|------------|----------------|------:|--------|
| `ticket.TicketStateMachineTest` | All 81 state pairs match the spec; terminal states have no exits; nulls and self-transitions rejected | 96 | ✅ pass |
| `ticket.TicketServiceTest` | Transitions persisted in order with timestamps; invalid and stale transitions rejected and not written; cancel; finish time and duration | 7 | ✅ pass |
| `queue.JobQueueTest` | Claim, FIFO, one live job per ticket, reschedule / `run_after`, lease recovery, fail | 8 | ✅ pass |
| `queue.JobQueueConcurrencyTest` | 16 threads × 300 jobs: every job claimed once and processed once, in parallel | 2 | ✅ pass |
| `queue.WorkerTest` | Complete / reschedule outcomes; a crashing handler is retried, then the job fails | 4 | ✅ pass |
| `fake.FakeBehaviorTest` | succeed / fail / fail-then-succeed, per-ticket counting, scripts, usage reported on failure | 7 | ✅ pass |
| `integration.BranchPolicyTest` | Only `factory/<id>`; main, master and others rejected, including by the fake GitHub | 9 | ✅ pass |
| `intake.GitHubPollerTest` | Only labeled issues in the configured repo; no duplicates; no id gaps | 2 | ✅ pass |
| `pipeline.TicketPipelineTest` | Happy path through every state; retries; checks loop; cost / turns guardrails; approval wait; PR closed; cancellation; 20 tickets on 3 workers | 15 | ✅ pass |
| `pipeline.HardTimeoutTest` | A 10s agent is cut off by a 1s hard timeout | 1 | ✅ pass |
| `e2e.FactoryEndToEndTest` | **Fake issue → poller → worker → DONE through every state; another → FAILED; stats show both** (total 2, 50%, costs, tokens, durations add up) | 2 | ✅ pass |
| `web.DashboardControllerTest` | /, /tickets (with filter), /tickets/{id}, 404, /stats, cancel, approve, fake issue API with validation | 8 | ✅ pass |
| `demo.DemoSeederTest` | Seeded paths are legal; history is consistent; stats are 12 / 4 / 2 | 2 | ✅ pass |
| `FactoryApplicationTests` | Context starts; Flyway creates the schema | 1 | ✅ pass |

### Manual and other checks

| Check | Result | Notes |
|-------|--------|-------|
| Docker available? | ✅ yes, after starting `dockerd` | Docker Hub returned 429, so images were pulled from `mirror.gcr.io` (DECISIONS #2, #3) |
| App started for real (`java -jar`, demo profile, real Postgres) | ✅ pass | Seeded 18 tickets; the 4 live ones finished as DONE, DONE (1 retry), AWAITING_APPROVAL, FAILED (cost guardrail) |
| `curl /tickets`, `/stats`, `/tickets/20`, `/tickets/21` | ✅ 200, expected content | 22 tickets, 73.7%, $11.11, full history with retry line, Approve and Cancel buttons |
| `curl /tickets/9999`, `/`, `/app.css` | ✅ 404, 302 → /tickets, 200 | |
| Approve via POST; new issue via `POST /api/fake/issues` | ✅ pass | Both reached DONE with PRs on `factory/<id>` → `main` |
| `docker compose up` (app + postgres) | ✅ pass (runtime) | Healthy; dashboard showed 22 tickets / 73.7% / $11.11; no ERROR logs |
| `docker compose build` (Maven stage in the container) | ⚠️ **not verified** | The container can't reach Maven Central through the sandbox proxy. The runtime stage was verified with a locally built jar (DECISIONS #20) |
| CI workflow YAML | ✅ parses; push, pull_request and workflow_dispatch triggers | The real run is on GitHub (see the PR checks) |
| `mvn clean verify` from a clean clone, Ryuk enabled (like CI) | ✅ BUILD SUCCESS, 164 tests, ~33s | |

### Skipped / not built (by design: Phase 2)

Real Docker sandbox, real Claude Code agent, real GitHub API, real checks runner, reviewer agent, dashboard auth.
Each has a stub or a TODO, listed in README.md and docs/DESIGN.md.
