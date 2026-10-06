# Design

## Overview

```
 GitHub (fake)          ┌──────────────── app (Spring Boot, virtual threads) ────────────────┐
 issues w/ label ──poll──▶ GitHubPoller ──▶ TicketIntake ──┬─▶ tickets (RECEIVED)             │
                        │                                  └─▶ jobs (PENDING)                 │
                        │                                                                     │
                        │  WorkerPool ── N × Worker ── claim (SKIP LOCKED) ──▶ TicketPipeline │
                        │                                                      │  │  │  │     │
                        │       SandboxRunner ◀─────────────────────────────────┘  │  │  │     │
                        │       AgentRunner   ◀────────────────────────────────────┘  │  │     │
                        │       ChecksRunner  ◀───────────────────────────────────────┘  │     │
                        │       GitHubClient  ◀──────────── open PR / poll approval ─────┘     │
                        │                                                                     │
                        │  Dashboard (Thymeleaf): /tickets  /tickets/{id}  /stats             │
                        └─────────────────────────────────┬───────────────────────────────────┘
                                                          │
                                                   PostgreSQL (Flyway)
                                          tickets · ticket_transitions · jobs
```

Code is under `src/main/java/com/ticketfactory/`:

| Package | Contents |
|---------|----------|
| `ticket` | `TicketState`, `TicketStateMachine` (transition table), `TicketService` (the only writer of state), `TicketRepository` |
| `queue` | `JobQueue` (Postgres queue), `Worker`, `WorkerPool`, `JobHandler`/`JobOutcome` |
| `integration` | The four interfaces, `TicketContext`, `BranchPolicy`, `StepFailedException`; `phase2/` holds the stubs |
| `fake` | Fakes, `FakeBehavior`/`FakeMode`, `FakeScript` (per-ticket directives), `FakeGitHubController` |
| `intake` | `GitHubPoller`, `TicketIntake` |
| `pipeline` | `TicketPipeline` (the orchestrator), `GuardrailExceededException` |
| `stats`, `web` | `StatsService`, dashboard controller, `Fmt` template helpers |
| `demo` | `DemoSeeder` (`demo` profile only) |

## State machine

```
RECEIVED ─▶ SANDBOX_READY ─▶ CODING ─▶ CHECKS ─▶ PR_OPENED ─▶ AWAITING_APPROVAL ─▶ DONE
                               ▲          │
                               └──────────┘ checks failed: back to CODING with the output as feedback

every non-terminal state ─▶ FAILED | CANCELLED          DONE, FAILED, CANCELLED are terminal
```

- The table lives in one place, `TicketStateMachine`. Anything not in it throws `InvalidTransitionException`.
  `TicketStateMachineTest` checks all 81 (from, to) pairs against an independently written copy of the table.
- `TicketService.transition(id, expectedFrom, to, reason)` validates the move, then runs
  `UPDATE tickets SET state = :to WHERE id = :id AND state = :expectedFrom`. If the row was already moved (for example
  a user cancelled it during a step), zero rows match and a `ConcurrentTransitionException` is thrown, so a stale
  writer can never overwrite a newer state. In the same transaction it appends to `ticket_transitions`
  (`from_state`, `to_state`, `reason`, `created_at`).
- Moving to a terminal state sets `finished_at` and `duration_ms` (from first pickup, `started_at`, to finish) and,
  for FAILED, `failure_reason`.

## Job queue

One table, `jobs`. A worker claims a job with a single statement:

```sql
UPDATE jobs SET status='RUNNING', locked_by=:worker, locked_at=now(), attempts=attempts+1
WHERE id = (SELECT id FROM jobs WHERE status='PENDING' AND run_after <= now()
            ORDER BY run_after, id LIMIT 1 FOR UPDATE SKIP LOCKED)
RETURNING *;
```

- `SKIP LOCKED` means concurrent claimers never block on, or receive, the same row. `JobQueueConcurrencyTest` runs 16
  threads against 300 jobs and checks that every job is claimed exactly once and processed exactly once.
- `uq_jobs_one_active_per_ticket` is a partial unique index on `jobs(ticket_id) WHERE status IN
  ('PENDING','RUNNING')`, so a ticket can never have two live jobs, even across app instances.
- A job is a ticket's whole journey. When the pipeline has to wait (backoff before a retry, or polling for
  approval), the job is put back to `PENDING` with a later `run_after`. No worker thread sleeps while waiting.
- **Leases (M0).** A claim is a lease identified by `(id, locked_by, attempts)`. While the handler runs, a heartbeat
  thread renews `locked_at` every `lease-timeout / 3`, so a step can run far longer than the lease (a 20-minute agent
  run is fine with a 10-minute lease). `WorkerPool`'s reaper only returns a job to `PENDING` when its heartbeat has
  stopped for a whole lease, which means its worker died. The pipeline then resumes from the persisted ticket state.
- **Fencing (M0).** Heartbeat, complete, fail and reschedule all update `WHERE id AND status='RUNNING' AND
  locked_by AND attempts` match the lease. A worker that lost its lease changes nothing and logs it. When a heartbeat
  finds the lease gone, `JobContext.stillOwned()` turns false; the pipeline checks it before every step and while
  waiting for the agent, and returns `JobOutcome.Abandon` without touching the ticket again.
- **Database time (M0).** Every queue decision (due, lease age, backoff) uses Postgres `now()`, so app instances
  with skewed clocks agree. Ticket timestamps shown on the dashboard still come from the app `Clock`.
- `TwoInstancesTest` runs two full app contexts against one database, with agent runs 2.5× the lease and aggressive
  reapers in both, and checks that every ticket got exactly one agent run.
- If the handler throws unexpectedly (a bug or a DB outage), the worker reschedules the job, and after
  `max-job-attempts` (5) marks it `FAILED`.

## Pipeline

`TicketPipeline.handle(job)` loads the ticket and repeats one step at a time until the ticket is terminal or must
wait. Each step reads only persisted state, so steps can be resumed safely:

| State | Step |
|-------|------|
| RECEIVED | `SandboxRunner.prepare`, store sandbox id and branch `factory/<id>`, then SANDBOX_READY |
| SANDBOX_READY | then CODING |
| CODING | `AgentRunner.run` with the remaining turn and cost budget and the last checks output; record usage; check guardrails; success goes to CHECKS |
| CHECKS | `ChecksRunner.run`. Pass: open a PR (skipped if one was already opened), then PR_OPENED. Fail: store the output, `retries++`, back to CODING (or FAILED once retries run out) |
| PR_OPENED | comment on the issue, then AWAITING_APPROVAL |
| AWAITING_APPROVAL | `getPullRequestStatus`: APPROVED goes to DONE, CLOSED goes to CANCELLED, PENDING reschedules the job (`approval-poll-interval`) |

The sandbox is destroyed once the ticket is terminal.

**Failure handling.** A `StepFailedException` (sandbox didn't start, API error, agent reported failure) increments
`retries` and reschedules with linear backoff (`retry-backoff × retries`). Once `retries > max-retries`, the ticket
goes to FAILED with the step and the last error as the reason. Failed checks use the same retry counter, so
`max-retries` caps the total rework per ticket.

## Guardrails

Configured under `factory.guardrails`, enforced in `TicketPipeline`, and failing the ticket immediately with no retry:

| Guardrail | Enforcement |
|-----------|-------------|
| `max-cost-usd` | After every agent run, `tickets.cost_usd` (the sum of all runs) is compared to the limit. The remaining budget is also passed to the agent in `AgentRequest.maxCostUsd`. |
| `max-turns` | Same, using `tickets.turns`; the remaining turns are passed as `AgentRequest.maxTurns` (maps to `--max-turns` in Phase 2). |
| `hard-timeout` | Checked before every step against `started_at`. The agent call runs on a virtual thread with `Future.get(remaining)`, so a hung agent is cut off and interrupted. Time in AWAITING_APPROVAL is not counted, because that is a human's time, not the agent's. |

The fake agent reports turns, input and output tokens, and cost on every run, including failed runs, so every limit is
covered by a test (`TicketPipelineTest`, `HardTimeoutTest`).

## Integration modes (M0)

`factory.integrations` (`FACTORY_INTEGRATIONS`) picks the implementations:

- `fake` (default): `FakeIntegrationsConfig` wires the four fakes, and the fake-only web endpoints exist
  (`FakeApprovalController` for the Approve button, `FakeGitHubController` for `/api/fake/**`). The dashboard core
  (`DashboardController`) no longer depends on any fake.
- `real`: `RealIntegrationsConfig` is active and no fake bean or endpoint is registered. Until M2–M5 land, startup
  fails with "factory.integrations=real is not implemented yet". Real implementations get registered there.

## Tickets are attempts (M1)

A ticket is one **attempt** at an issue (`tickets.attempt`, numbered from 1). Each attempt snapshots the issue's
title and body at trigger time, gets its own branch `factory/<ticket-id>`, sandbox, PR and usage, and goes through
the state machine once.

- **New attempt rule** (`TicketRepository.insertAttempt`, one SQL statement): insert only if the issue has no
  unfinished attempt *and* the trigger is newer than the latest attempt's `triggered_at`. The trigger time is when
  the label was last applied (`GitHubClient.Issue.triggeredAt`), so removing and re-applying the label starts the
  next attempt, while a finished issue that just keeps its label is never retried in a loop.
- `uq_tickets_one_active_per_issue` (partial unique index) guarantees one unfinished attempt per issue even with
  concurrent pollers; `UNIQUE (repo, issue_number, attempt)` keeps numbering unique.
- The dashboard shows "Attempt n of m" and links every attempt at the issue.

## Reconciliation (M1)

Every poll compares running attempts with the issue tracker. An attempt whose issue was closed or lost the label is
cancelled **only while it has no PR yet** (RECEIVED, SANDBOX_READY, CODING, CHECKS), and only after the issue has been missing from **two polls in a row**: GitHub's listing lags behind writes by a few seconds, so one miss can be a stale read. Once a PR exists, the PR
decides: merging a PR with "Closes #N" closes the issue, and that must not cancel a ticket that is about to be DONE.
`listOpenIssues` must be complete (implementations page internally) and throw rather than return a partial list;
a failed listing cancels nothing.

## Contract tests (M1)

`src/test/java/com/ticketfactory/contract/` holds one abstract suite per interface: `GitHubClientContract`,
`SandboxRunnerContract`, `AgentRunnerContract`, `ChecksRunnerContract`. Each fake has a subclass that runs on every
build; each real implementation gets one in its milestone. The contracts pin down the behavior the pipeline relies
on: idempotent `prepare` and `openPullRequest` (so a crash between a side effect and the DB write never duplicates
work), `BranchPolicy` enforcement, complete issue listings with trigger times, staying within `maxTurns`, usage
reported even on failure, prompt stop on interrupt, and bounded checks output. Cost is deliberately not promised:
only the pipeline's guardrail can enforce it.

## Real GitHub client (M2)

`integration/github/`: `GitHubRestClient` over `GitHubHttp` (JDK `HttpClient` + Jackson), wired by
`RealIntegrationsConfig`.

| Concern | How |
|---------|-----|
| Auth | `GitHubAppAuth` signs an RS256 JWT with the App key (PKCS#1 as GitHub downloads it, or PKCS#8), exchanges it for an installation token, caches it until 5 minutes before expiry. Or a static `GITHUB_TOKEN`. App wins if any App setting is present. |
| Complete listings | `getAllPages` follows `Link: rel="next"`; any failing page fails the whole call, so the poller never reconciles against a partial list. Pull requests returned by the issues API are skipped. |
| Rate limit | GETs are cached by ETag and sent with `If-None-Match`; a `304` reuses the body and is free. Label events and permissions are fetched only for issues whose `updated_at` changed; permissions are cached 10 minutes. |
| Rate-limit errors | `403/429` with `x-ratelimit-remaining: 0` (wait until `x-ratelimit-reset`) or `Retry-After` become `RateLimitedException`. The pipeline reschedules the job for that long **without** spending a retry. `5xx` and network errors are ordinary retryable `StepFailedException`s; other `4xx` are `GitHubApiException` with GitHub's message and `errors[]` details. |
| Who may trigger | The latest `labeled` event for the trigger label gives the trigger time and the actor. The actor needs at least `min-labeler-permission` (default write) on the repo, or the issue is ignored, with one warning per trigger. |
| Idempotent PR | Look up an open PR for `owner:head` first; if create still answers `422 ... already exists` (a race), look it up again. |
| Approval | Merged = APPROVED; closed = CLOSED; otherwise the latest decisive review per reviewer counts: any outstanding CHANGES_REQUESTED = PENDING, else any APPROVED = APPROVED. Dismissed reviews drop out. |

Not in M2: pushing the branch. On real GitHub a PR needs the branch to exist, and the branch comes from the sandbox
(the agent commits there, the host pushes after `BranchPolicy`). That arrives with the Docker sandbox in M3, which is
also why `factory.integrations=real` still refuses to start.

**Tests:** `GitHubApiSimulator` is a small stateful HTTP server (JDK `HttpServer`) that behaves like the GitHub
endpoints above, including paging, ETags, label events, permissions, App token exchange and injectable faults.
`SimulatedGitHubClientContractTest` runs the shared contract over real HTTP; `GitHubRestClientTest` and
`GitHubAppAuthTest` cover the rest. `LiveGitHubClientContractTest` (tag `live`) runs the same contract against a
real throwaway repo from `.github/workflows/factory-live.yml`.

## Docker sandbox (M3)

`integration/docker/`: `DockerSandboxRunner` (docker-java over the Docker socket) and `HostGit`.

```
 factory host                                              sandbox container "factory-<ticket-id>"
 ────────────                                              ───────────────────────────────────────
 bare clone of owner/repo  ── git bundle of base ──exec──▶  /workspace/repo on factory/<ticket-id>
 (fetch/push with token)                                    (no network, no token, user 1000)
          ▲                                                           │ agent commits (M5)
          └──── push factory/<ticket-id> ◀── git bundle ──exec── ◀────┘
```

| Property | How |
|----------|-----|
| Isolation | `--network none`, user `1000:1000`, read-only root filesystem, `--cap-drop ALL`, `no-new-privileges`, memory (= memory+swap), CPU and pids limits. `/workspace` and `/tmp` are size-limited tmpfs owned by the sandbox user. |
| No credentials inside | The host holds the GitHub token (passed to git through environment config, never the command line) and does all fetching and pushing. Code moves as single-file git bundles streamed through `docker exec` stdin/stdout. Docker's archive API can't be used: it refuses to write into a read-only container and can't see tmpfs. |
| Never push main | `BranchPolicy.validateBranch` runs in `DockerSandboxRunner.publishBranch` **and** in `HostGit.pushBranch`; the push refspec is always `refs/heads/factory/<id>`. |
| Idempotent | `prepare` reuses a running sandbox whose repo is checked out (work in progress survives a worker crash). A stopped or half-prepared one has lost its tmpfs, so it is replaced. A failed `prepare` removes what it created. |
| Janitor | `SandboxJanitor` (every 10 minutes, all modes) removes sandboxes whose ticket finished or no longer exists. Running tickets keep theirs: lease recovery hands them to another worker. |
| Concurrency | Host git operations on the same repo are serialized per repo; different repos run in parallel. |

The image (default `buildpack-deps:bookworm-scm`) only needs git and a shell for now; it is pulled automatically on
first use. The checks runner (M4) and agent (M5) will need an image with the target repo's toolchain and the agent CLI,
and will run inside the sandbox through `DockerSandboxRunner.exec`.

**Network for the agent (M5):** the sandbox has none today. The agent will need to reach the model API, and nothing
else. That needs an egress proxy with a host allowlist, added with the agent in M5 rather than here (decision 44).

## Fakes

Each fake takes its behavior from three places, in priority order:

1. A `fake-<step>:` line in the issue body (`FakeScript`), e.g. `fake-checks: fail-then-succeed 2`
2. A runtime override set by tests (`setDefaultBehavior`)
3. Configuration: `factory.fakes.<step>.mode` = `succeed | fail | fail-then-succeed`, plus `failures-before-success`

Calls are counted per ticket, so fail-then-succeed is deterministic even with many tickets running at once.

## Data recorded per ticket

`tokens_input`, `tokens_output`, `cost_usd`, `turns`, `retries`, `created_at`, `started_at`, `finished_at`,
`duration_ms`, final `state` (the outcome), `failure_reason`, `branch_name`, `pr_number`/`pr_url`, `last_feedback`,
plus the full transition log and the job rows.

## Stats

- **Success rate** = DONE / (DONE + FAILED). CANCELLED tickets are left out, because a human stopped them.
- **Avg duration**: DONE and FAILED tickets.
- **Avg cost**: finished tickets (DONE, FAILED, CANCELLED). **Total cost**: all tickets, including running ones.

## Branch safety

`BranchPolicy.validatePullRequest(head, base)` rejects any head that isn't `factory/<something>`, is `main` or
`master`, or equals the base. The pipeline always uses `BranchPolicy.branchFor(ticketId)`, and the fake GitHub
enforces the policy too, so a Phase 2 client must call it as well.

## Phase 2 plan (not built)

1. `DockerSandboxRunner`: one container per ticket, repo cloned on `factory/<id>`, network limited to GitHub and the
   model API, CPU and memory limits, destroyed when the ticket ends.
2. `ClaudeCodeAgentRunner`: run Claude Code headless in the sandbox with `--max-turns` set to the remaining budget,
   parse the usage it reports into `AgentResult`, push commits to `factory/<id>`.
3. `SandboxChecksRunner`: run the target repo's build and test command in the sandbox.
4. `GitHubRestClient`: GitHub App auth from env, list issues by label, open PRs, read review state (approved, changes
   requested, closed), and comment. Use webhooks instead of polling if latency matters.
5. ~~A switch `factory.integrations=fake|real`~~: done in M0; real implementations register in `RealIntegrationsConfig`.
6. A reviewer agent before AWAITING_APPROVAL, and "changes requested" back to CODING. That needs a new
   AWAITING_APPROVAL → CODING transition, which is deliberately not allowed today.
7. Authentication on the dashboard and its POST actions (none in Phase 1).
