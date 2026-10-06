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
- Lease recovery: `WorkerPool` regularly returns `RUNNING` jobs whose `locked_at` is older than
  `factory.worker.lease-timeout` (10 minutes) to `PENDING`. The pipeline resumes from the persisted ticket state.
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
5. A switch `factory.integrations=fake|real` in place of `FakeIntegrationsConfig`.
6. A reviewer agent before AWAITING_APPROVAL, and "changes requested" back to CODING. That needs a new
   AWAITING_APPROVAL → CODING transition, which is deliberately not allowed today.
7. Authentication on the dashboard and its POST actions (none in Phase 1).
