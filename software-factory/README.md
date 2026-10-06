# Ticket-to-PR Software Factory (Phase 1)

A service that turns labeled GitHub issues into pull requests. It polls for issues with the `factory` label, creates
a ticket for each one, and moves the ticket through a fixed pipeline: sandbox, coding agent, checks, pull request,
human approval. Every step is recorded, along with tokens, cost, duration, retries and the final outcome. A dashboard
shows the results.

**Phase 1 uses fakes** for GitHub, the sandbox, the coding agent and the checks. That way the whole pipeline can be
run, demonstrated and tested without Docker sandboxes, a GitHub token or LLM costs. The fakes can be told to succeed,
fail, or fail N times and then succeed, so every path through the pipeline can be tested.

## Run it locally (3 commands)

Needs Docker with Compose.

```bash
cd software-factory
cp .env.example .env          # optionally change POSTGRES_PASSWORD
docker compose up --build
```

Open <http://localhost:8080>. `.env.example` turns on the `demo` profile, so you see 19 finished tickets right away (including an issue that failed and was fixed on its second attempt),
plus 4 live tickets that run through the pipeline in the first ~20 seconds. [MORNING.md](MORNING.md) has a 5-minute
tour.

**Without Docker for the app** (Java 21 + Maven, Postgres from Docker or anywhere else):

```bash
docker run -d --name factory-pg -e POSTGRES_DB=factory -e POSTGRES_USER=factory -e POSTGRES_PASSWORD=localdev -p 5432:5432 postgres:16-alpine
cd software-factory && SPRING_PROFILES_ACTIVE=demo DATABASE_PASSWORD=localdev mvn spring-boot:run
```

## Run the tests

```bash
cd software-factory
mvn clean verify
```

The tests need Docker: Testcontainers starts a real PostgreSQL 16, because the job queue depends on Postgres-only
features (`FOR UPDATE SKIP LOCKED`, partial unique indexes). That gives 287 tests in about a minute (the sandbox tests start real containers). CI
(`.github/workflows/factory-ci.yml`) runs the same command on every push and pull request.

## What is built vs. not built

| Area | Status |
|------|--------|
| State machine: 9 states, fixed transition table, invalid moves rejected, every transition stored with a timestamp | Built |
| Postgres job queue (`FOR UPDATE SKIP LOCKED`), one live job per ticket, heartbeat leases, fenced job updates, virtual-thread workers | Built |
| Interfaces `GitHubClient`, `SandboxRunner`, `AgentRunner`, `ChecksRunner` with configurable fakes | Built |
| GitHub poller: labeled issues become tickets; re-applying the label to a finished issue starts a new attempt; closing or unlabeling cancels a run that has no PR yet | Built (against the fake) |
| Contract tests: one shared suite per interface that fakes and real implementations must both pass | Built (fakes pass all 24) |
| Pipeline: retries with backoff, checks-failed loop back to coding, approval wait, cancellation | Built |
| Guardrails: max cost per ticket, max turns, hard timeout, max retries (all configurable) | Built |
| Per-ticket tokens, cost, turns, duration, retries, outcome, failure reason | Built |
| Branch policy: only `factory/<ticket-id>`, never `main` | Built |
| Dashboard: ticket list, ticket detail with history, stats; cancel and fake-approve buttons | Built |
| Demo profile with seeded and live tickets | Built |
| Docker Compose (app + postgres), GitHub Actions CI | Built |
| Docker sandbox (`DockerSandboxRunner`): one container per attempt, no network, no credentials, non-root, read-only root, all capabilities dropped, memory/CPU/process limits; code moves in and out as git bundles and the host pushes the branch; a janitor removes orphans | Built (M3), tested on real Docker |
| Real Claude Code agent | **Not built** (Phase 2): stub in `integration/phase2/ClaudeCodeAgentRunner` |
| Real GitHub client (`GitHubRestClient`): GitHub App or token auth, complete paged issue listing, ETag caching, trigger time and labeler permission from label events, idempotent PR opening, review-based approval, rate-limit handling | Built (M2), tested against a GitHub API simulator on every build and against real GitHub nightly (passing) |
| Real checks in the sandbox | **Not built** (Phase 2): stub in `integration/phase2/SandboxChecksRunner` |
| Reviewer agent, auth on the dashboard, metrics export | **Not built** |

## Configuration

Everything is set through environment variables; see [`.env.example`](.env.example) and
`src/main/resources/application.yml`. The main ones:

| Variable | Default | Meaning |
|----------|---------|---------|
| `FACTORY_INTEGRATIONS` | `fake` | `fake` (Phase 1) or `real` (Phase 2; fails at startup until sandbox, checks and agent exist) |
| `GITHUB_APP_ID`, `GITHUB_APP_INSTALLATION_ID`, `GITHUB_APP_PRIVATE_KEY_PATH` | none | Real GitHub via a GitHub App (recommended) |
| `GITHUB_TOKEN` | none | Real GitHub via a token, if no App is configured |
| `FACTORY_MIN_LABELER_PERMISSION` | `write` | Issues labeled by someone with less repo permission are ignored |
| `FACTORY_SANDBOX_IMAGE`, `FACTORY_SANDBOX_MEMORY`, `FACTORY_SANDBOX_CPUS` | `buildpack-deps:bookworm-scm`, `4g`, `2.0` | Sandbox container (real mode) |
| `FACTORY_WORK_DIR` | `/var/lib/factory` | Host-side bare clones of target repos (real mode) |
| `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` | `jdbc:postgresql://localhost:5432/factory`, `factory`, empty | Postgres connection |
| `FACTORY_REPO` / `FACTORY_TRIGGER_LABEL` | `example-org/example-repo` / `factory` | Which issues to pick up |
| `FACTORY_MAX_COST_USD` | `2.00` | Ticket fails once its total agent cost goes over this |
| `FACTORY_MAX_TURNS` | `40` | Ticket fails once its total agent turns go over this |
| `FACTORY_HARD_TIMEOUT` | `PT30M` | Wall-clock limit per ticket (time waiting for approval is not counted) |
| `FACTORY_MAX_RETRIES` | `3` | Retries for failed steps and failed checks, combined |
| `FACTORY_WORKER_THREADS` | `2` | Workers per app instance (you can run several instances safely) |
| `SPRING_PROFILES_ACTIVE` | none | `demo` seeds data |

There are no secrets in the repo. Phase 2 will read `GITHUB_TOKEN` and `ANTHROPIC_API_KEY` from the environment.

## Try a scenario by hand

With the app running, open an issue on the fake GitHub. Lines starting with `fake-` in the body tell the fakes what
to do for this ticket:

```bash
curl -X POST localhost:8080/api/fake/issues -H 'Content-Type: application/json' \
  -d '{"number": 501, "title": "Fix the flaky test", "body": "fake-checks: fail-then-succeed 1"}'
```

| Directive | Effect |
|-----------|--------|
| `fake-sandbox: fail` / `fail-then-succeed N` | Sandbox fails to start (always / first N times) |
| `fake-agent: fail` / `fail-then-succeed N` | Agent reports failure (its usage is still counted) |
| `fake-agent-cost: 3.50` | Dollars per agent run (above 2.00, trips the cost guardrail) |
| `fake-agent-turns: 50` | Turns per agent run (above 40, trips the turn guardrail) |
| `fake-agent-delay: PT5S` | Agent takes this long (use with a short `FACTORY_HARD_TIMEOUT` to trip the timeout) |
| `fake-checks: fail` / `fail-then-succeed N` | Checks fail, so the ticket goes back to CODING with the output as feedback |
| `fake-github: fail-then-succeed N` | Opening the PR fails N times |
| `fake-approval: pending` / `closed` | PR waits for the Approve button / is closed, which cancels the ticket |

Other fake GitHub controls: `POST /api/fake/issues/{n}/relabel` (re-apply the label: a finished issue gets a new
attempt on the next poll) and `POST /api/fake/issues/{n}/close` (a running attempt without a PR is cancelled).

## Docs

- [MORNING.md](MORNING.md): 5-minute hands-on check, plus the test results table
- [docs/DESIGN.md](docs/DESIGN.md): architecture, state machine, queue, guardrails, Phase 2 plan
- [docs/DECISIONS.md](docs/DECISIONS.md): every judgment call made during the build
- [docs/PHASE2-PLAN.md](docs/PHASE2-PLAN.md): architecture review, Phase 2 readiness findings, test strategy, milestone plan
