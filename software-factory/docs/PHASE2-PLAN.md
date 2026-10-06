# Architecture review and Phase 2 plan

Review of the Phase 1 code as merged to `main` (`2ede371`), how ready it is for real integrations, how to test
everything, and the order to build it in. File references are relative to `software-factory/`.

**Verdict:** the core is sound and worth keeping: the state machine, the Postgres queue, the resumable pipeline and the
four interfaces. Phase 1 has **two blocking defects** that stay hidden while the fakes are instant but will break as
soon as a real agent runs for minutes (findings 1 and 2), plus a set of seams to cut before real code is plugged in.
Fix those first (milestone M0), then add one real integration at a time behind contract tests.

---

## 1. What is solid and should stay

| Part | Why it holds up for Phase 2 |
|------|-----------------------------|
| `TicketStateMachine` + guarded `TicketService.transition` | One table, exhaustively tested, and a stale writer can't overwrite a newer state. Adding states or edges is a one-line change plus a test update. |
| Pipeline resumes from persisted state (`TicketPipeline.handle`) | Crashes, retries and lease recovery continue where the ticket stopped. That is exactly what slow, flaky real integrations need. |
| Postgres queue with `SKIP LOCKED` and one live job per ticket (partial unique index) | Proven with 16 workers × 300 jobs. No Kafka or Redis to run. Scales to several app instances. |
| Waiting reschedules the job instead of sleeping a thread | Approval waits and backoff cost no threads. |
| Four narrow interfaces with fakes that can fail on purpose | Real implementations plug in without touching the pipeline, and the fakes remain the test doubles. |
| Guardrails as config, enforced in one place | Remaining budget is already passed to the agent (`AgentRequest.maxTurns`, `maxCostUsd`), ready for `--max-turns`. |
| `BranchPolicy` | Safety rule lives in one function that every client must call. |

## 2. Findings

Severity: **P0** = must fix before any real integration runs. **P1** = must fix before real repos or users.
**P2** = should fix during Phase 2.

### P0: blocking

**1. A slow agent run gets processed by two workers at once (verified).**
`lease-timeout` is 10 minutes (`application.yml:34`), `hard-timeout` is 30 minutes (`:43`), and nothing renews the
lease while a step runs. After 10 minutes `WorkerPool.reapExpiredLeases` returns the job to `PENDING` and another
worker claims it. With a real agent that means two sandboxes and two agents pushing to the same `factory/<id>`
branch, and the cost is spent twice. I reproduced it with a throwaway test: claim by worker A → lease expires → worker
B claims the same ticket. Fakes return instantly, so the Phase 1 suite never hits this.
*Fix:* a heartbeat that renews `locked_at` every `lease/3` while the job runs, and the reaper only reclaims jobs
whose heartbeat stopped (worker died). Keep `lease-timeout` independent of step length.

**2. Job updates are not fenced to the worker that owns the job (verified).**
`JobQueue.complete`, `fail` and `reschedule` update `WHERE id = :id` (`JobQueue.java:79`, `:123`), without checking
`locked_by`. In the repro above, worker A finishing late marked worker B's live job `DONE`. That ends the job while
B is still working and lets a new job be enqueued for the same ticket.
*Fix:* add `AND locked_by = :worker AND attempts = :attempt` to every finishing update. If 0 rows match, the worker
lost the job: log it and stop, without touching the ticket. Ticket transitions are already fenced by
`expectedFrom`; jobs need the same treatment.

**3. Web layer depends on the fake.**
`DashboardController` injects the concrete `FakeGitHubClient` (`DashboardController.java:4`, `:35`, `:108`), and
`FakeIntegrationsConfig` is registered unconditionally. Switching to real integrations would fail at startup or ship
a "fake approve" button to production.
*Fix:* `factory.integrations=fake|real` with `@ConditionalOnProperty` on `FakeIntegrationsConfig` and on a new
`RealIntegrationsConfig`. Move the Approve action and `/api/fake/**` into a fake-only controller. In real mode,
approval comes from GitHub.

### P1: before real repos or users

**4. Side effects and state writes aren't atomic, so a crash between them leaks or duplicates work.**
- `prepareSandbox` creates the sandbox, then stores its id (`TicketPipeline.java:129-130`). A crash in between leaves
  an orphaned container, and the retry creates a second one.
- Opening a PR is guarded by `prNumber == null` (`:200`), but a crash after GitHub created the PR and before
  `setPullRequest` produces a duplicate PR on retry.

*Fix:* make every external call idempotent by key. Name the sandbox `factory-<ticketId>` and have `prepare` reuse it
if it exists. `openPullRequest` looks up an existing open PR for `head` first. Add a janitor that destroys sandboxes
with no live ticket.

**5. Cancelling doesn't stop a running agent.** Cancel flips the state, but the agent keeps running (and spending)
until it returns. `future.cancel(true)` (`:170`, `:181`) only interrupts the Java thread, not a `docker exec`
process. *Fix:* `AgentRunner` takes a cancellation handle; the real runner kills the process and the container.
While the agent runs, the pipeline checks every few seconds whether the ticket was cancelled.

**6. One ticket per issue, forever.** `UNIQUE (repo, issue_number)` (`V1__init.sql:25`) means a FAILED issue can
never be retried, even after someone improves the issue text. *Fix:* split `tickets` (one per issue) from
`attempts` (one per run; state, usage, sandbox and PR live here). Re-labeling an issue starts a new attempt. Needs a
Flyway V2 migration that turns existing rows into attempt #1.

**7. The poller only adds tickets.** If an issue is closed, unlabeled or edited, the running ticket doesn't notice.
`listOpenIssues` (`GitHubClient.java:12`) has no paging or since-cursor. *Fix:* reconcile on each poll (closed or
unlabeled → cancel). Page with `since` and an ETag. Webhooks later (finding 12).

**8. Security model for untrusted input (none needed in Phase 1, required before real issues).**
- The issue body goes straight into the agent prompt. Anyone who can get the label onto an issue can try prompt
  injection. Only maintainers should be able to apply the label (check the labeler's permission via the API), and
  this must be documented.
- The sandbox must not hold the GitHub write token or any other secret. The agent commits locally; the **host**
  pushes the branch with the token, after `BranchPolicy`.
- Sandbox network egress allowlist: the model API, the package registries the repo needs, nothing else. CPU,
  memory, disk and pids limits. Run as non-root, read-only root filesystem.
- The dashboard has no auth and its POST actions have no CSRF protection. Add Spring Security (GitHub OAuth or an
  SSO proxy) before it can reach real data.

**9. Mixed clocks.** Some timestamps come from the app `Clock`, others from Postgres `now()`
(`TicketRepository.java:111-133`), and claim compares `run_after` with the app clock. Clock skew between instances
shifts scheduling, and tests can't control time. *Fix:* use database time for queue and lease decisions, and the
`Clock` only for display and duration math.

### P2: during Phase 2

10. **Observability.** No metrics, no ticket id in logs. Add Micrometer counters and timers (tickets by outcome,
    step duration, cost, guardrail trips, queue depth, lease losses), `ticketId` and `jobId` in MDC, and an
    actuator `prometheus` endpoint.
11. **Executor lifecycle.** The `agentExecutor` in `TicketPipeline` (`:53`) is never shut down. Make it a bean
    that is closed on shutdown.
12. **Approval polling cost.** Every ticket waiting for approval calls GitHub every 5 seconds. With real API rate
    limits, poll every few minutes and batch, then switch to webhooks (`pull_request_review`, `issues`) with
    polling kept only as a fallback.
13. **Changes requested.** A real review can ask for changes. Add the `AWAITING_APPROVAL → CODING` edge (with the
    review comments as `lastFeedback`) together with the reviewer agent. It is deliberately absent today
    (DECISIONS #13).
14. **Production defaults.** `spring.thymeleaf.cache: false` is in the base config (`application.yml:14`); move it
    to a dev profile. Dashboard lists cap at 500 rows with no paging. Stats scan the whole table on every page view;
    fine up to thousands of tickets.
15. **Multi-repo.** `factory.repo` is a single value. Make it a list now, while it's cheap (the `repo` column already
    exists).

## 3. How to test everything

Each layer catches a different kind of bug. Most of the cost sits in the bottom two layers, which run on every push;
the expensive layers run less often.

| Layer | What it proves | Tooling | When it runs |
|-------|----------------|---------|--------------|
| **1. Unit** | State machine, branch policy, guardrail math, fake behaviors, prompt building, usage parsing | JUnit 5 | Every push (exists) |
| **2. Integration (DB)** | Queue claim, fencing, heartbeat, lease recovery, migrations, stats SQL | Testcontainers Postgres | Every push (exists; add the new cases below) |
| **3. Contract tests** | Fake and real implementations behave the same for each interface: idempotent `prepare`, PR lookup before create, errors become `StepFailedException`, `BranchPolicy` enforced | One abstract `*ContractTest` per interface, with a fake subclass and a real subclass | Fake subclass on every push; real subclass nightly or when its env vars are set |
| **4. Component (real dependency)** | Each real integration alone: `DockerSandboxRunner` against real Docker; `GitHubRestClient` against a dedicated test repo (or WireMock with recorded responses); `SandboxChecksRunner` on a sample repo | Testcontainers / Docker, WireMock, a test GitHub repo | Docker ones on every push (GitHub runners have Docker); live GitHub nightly |
| **5. Agent evaluation** | The real agent fixes realistic tickets inside the limits | A fixture repo with ~10 seeded issues of known difficulty, run headless, scored on checks passing, cost, turns, time | Nightly or on demand. Costs money, so it gets its own budget cap |
| **6. Failure injection** | The guarantees hold when things break: kill a worker mid-step, kill the DB connection, Docker daemon restart, GitHub 502/403/rate limit, agent hangs, user cancels mid-run | Fakes in fail modes; Testcontainers `pause`/`stop`; Toxiproxy for the DB link | Every push for fake-driven cases; nightly for container chaos |
| **7. Multi-instance** | Two app instances against one DB never double-process (the finding 1 and 2 class of bug) | Two Spring contexts or two app containers, 200 tickets with slow fakes and a short lease | Every push (fast version) |
| **8. End-to-end staging** | Real issue on a sandbox GitHub repo → real PR → approve → DONE; and the failure paths | The app deployed with real integrations against `<owner>/factory-playground` | Before each release, plus a nightly smoke run |
| **9. Security** | No secret reaches the sandbox; egress is blocked; the dashboard requires login; label applied by a non-maintainer is ignored | Tests that run `env` and `curl` inside the sandbox and expect failure; MockMvc security tests | Every push |
| **10. Load** | Queue and dashboard stay healthy with 1,000+ tickets and 10 workers | Fake-mode load script; measure claim latency and stats query time | Before pilot |

**Test cases that must exist before Phase 2 code merges** (each one fails today or is missing):

- Lease heartbeat: a job whose step runs longer than `lease-timeout` is **not** reclaimed while its worker is alive,
  and **is** reclaimed after the worker dies.
- Fencing: a worker that lost its job cannot complete, fail or reschedule it (this is the repro above, turned into
  a permanent test).
- Crash between sandbox creation and the DB write: a retry reuses the sandbox, and no container is left over.
- Crash between PR creation and the DB write: a retry finds the existing PR and doesn't open a second one.
- Cancel during a long agent run stops the agent within N seconds and records the cost spent so far.
- Two app instances, slow agent, short lease: every ticket gets exactly one agent run per attempt.
- App boots with `factory.integrations=real` and no fake beans or `/api/fake` routes present (and the opposite in
  fake mode).
- Issue closed or unlabeled mid-run → ticket CANCELLED.

**Make CI match the layers:** keep `mvn clean verify` (layers 1–3 fake, 4 Docker, 6–7 fast, 9) on every push and PR.
Add a scheduled nightly workflow for live GitHub contracts, agent evaluation and staging smoke, with secrets from
repository secrets, its own spend cap, and a summary of cost and pass rate.

## 4. Execution plan

Each milestone is one PR, merged only when its exit criteria are met and CI is green. Estimates assume one
engineer; they are rough.

| # | Milestone | Main work | Exit criteria | Est. |
|---|-----------|-----------|---------------|------|
| **M0** ✅ | **Harden the core** (done, see below) | Findings 1, 2, 3, 9, 11. Heartbeat and fenced job updates; `factory.integrations` switch; fake-only controller; DB time for scheduling; executor bean | All "must exist" tests for leases, fencing and mode switching pass; multi-instance test passes | 2–3 days |
| **M1** ✅ | **Contract tests + attempt model** (done, see below) | Abstract contract test per interface, run against the fakes; Flyway V2 for `attempts` (finding 6); poller reconciliation and paging (finding 7) | Fakes pass all contracts; V2 migrates a DB seeded by the demo without loss; re-labeling a failed issue starts attempt #2 | 3–4 days |
| **M2** ✅ | **Real GitHub client** (done; live run passed, see below) | `GitHubRestClient` (GitHub App auth from env), issues by label with paging, labeler permission check, open or find PR, review status, comments; host-side push | Contract suite passes against WireMock on every push and against a live test repo nightly; rate limits handled | 3–4 days |
| **M3** ✅ | **Docker sandbox** (done, see below) | `DockerSandboxRunner`: one container per attempt, named `factory-<id>`, idempotent, resource limits, non-root, egress allowlist, no secrets inside; janitor for orphans | Contract and security tests pass on real Docker in CI; killing the app mid-step leaves no orphan after the janitor runs | 4–5 days |
| **M4** ✅ | **Real checks** (done, see below) | `SandboxChecksRunner`: per-repo check command from config (e.g. `.factory.yml`), output truncation, timeout | Passes and fails correctly on a sample repo with a known failing test | 1–2 days |
| **M5** ✅ | **Claude Code agent** (done; live evaluation passed, see below) | `ClaudeCodeAgentRunner`: headless run in the sandbox with `--max-turns`, usage parsed into `AgentResult`, cancellation kills the process (finding 5), feedback loop from failed checks | Agent eval: at least 6 of 10 fixture issues reach DONE within limits; guardrails trip correctly on a deliberately oversized ticket; cancel stops spending within 10s | 4–6 days |
| **M6** ✅ | **Security and operations** (done, see below) | Dashboard auth + CSRF (finding 8); metrics and MDC (finding 10); webhooks with polling fallback (finding 12); production config (finding 14) | Security tests pass; Prometheus shows queue depth, cost and outcomes; one load test run at 1,000 tickets | 3–4 days |
| **M7** | **Pilot** | Run on one real low-risk repo with a small daily cost cap; reviewer agent and changes-requested edge (finding 13) can follow here | Two weeks of real tickets; success rate, cost per ticket and failure reasons reviewed weekly; no double runs, no leaked sandboxes, no secret exposure | 2 weeks elapsed |

Order matters: M0 first (it removes the defects that would corrupt every later test), contracts before any real
client, and the agent last, because it is the most expensive to test and depends on the sandbox and checks being
trustworthy.

**Decisions needed from the owner before M2:**
1. Test GitHub repo name, and whether to use a GitHub App (recommended) or a personal token.
2. Monthly budget for nightly agent evaluation and the pilot.
3. Which real repo the pilot targets.
4. Dashboard login method (GitHub OAuth is the natural fit).

## M0 status: done

| Finding | What changed | Proven by |
|---------|--------------|-----------|
| 1. Lease expires during long steps | Heartbeat renews the lease every `lease-timeout / 3` (`Worker.heartbeatLoop`) | `WorkerTest.heartbeatKeepsALongJobOwnedWhileReapersRun`, `JobQueueTest.heartbeatKeepsTheLeaseFromExpiring`, `TwoInstancesTest` |
| 2. Job updates not fenced | All job writes are fenced on `(id, locked_by, attempts)`; `JobContext.stillOwned()`; `JobOutcome.Abandon` | `JobQueueTest.workerThatLostItsLeaseCannotFinishTheJob` (the original repro, now a permanent test), `sameWorkerIdOnALaterAttemptIsStillFenced`, `WorkerTest.handlerSeesLeaseLossAndItsResultIsDiscarded`, `TicketPipelineTest.losingTheLeaseDuringAnAgentRunAbandonsWithoutTouchingTheTicket` |
| 3. Web layer depends on the fake | `factory.integrations=fake\|real`; `FakeApprovalController`; `RealIntegrationsConfig` fails fast; `DemoSeeder` works without the fake | `IntegrationModeTest` (4 tests) |
| 9. Mixed clocks | Queue uses Postgres `now()` only; `JobQueue` no longer takes a `Clock` | Existing queue tests on real Postgres |
| 11. Executor lifecycle | `agentExecutor` is a bean, shut down with the context | Context shutdown in every test run |
| 5 (partly) | The agent wait reacts to cancellation within 250 ms | `TicketPipelineTest.cancellingDuringALongAgentRunStopsWaitingForIt` |

Mutation check: with the heartbeat turned off, `TwoInstancesTest` and the two `WorkerTest` lease tests fail; with it
on, they pass. So these tests guard the fix.

## M1 status: done

| Item | What changed | Proven by |
|------|--------------|-----------|
| Contract tests | Abstract suites for all four interfaces in `src/test/.../contract/`; the fakes pass all 24 tests | `Fake*ContractTest` (4 classes) |
| 4. Idempotent side effects | Fake sandbox reuses a ticket's sandbox; `openPullRequest` returns the open PR for the same branch (both now contract requirements) | `TicketPipelineTest.sandboxLeftByACrashedRunIsReusedNotDuplicated`, `prOpenedByACrashedRunIsFoundNotDuplicated` |
| 6. One ticket per issue | V2 migration: `attempt`, `triggered_at`, one unfinished attempt per issue; re-applying the label starts the next attempt (decisions 28–30) | `AttemptsTest` (4), `MigrationTest` (Phase 1 data through V2), `DemoSeederTest` |
| 7. Poller only adds | Reconciliation: closed or unlabeled issue cancels an attempt without a PR; a failed listing cancels nothing; listing must be complete | `GitHubPollerTest` (4 new) |
| Janitor for orphaned sandboxes (from finding 4) | Not done here: it needs a real sandbox to look for orphans in. Already part of M3. | — |

Mutation check: breaking PR idempotency, the "no cancel once a PR exists" rule, the trigger-time rule, or the agent
turn cap each makes its tests fail.

Live check (demo mode, real Postgres): Flyway applied V1 and V2; re-labeling the failed issue #903 started attempt 2;
closing issue #960 while its agent was running cancelled it, and the worker's job finished 0.12 s later.

## M2 status: done

| Item | What changed | Proven by |
|------|--------------|-----------|
| `GitHubRestClient` | Issues by label (complete paging, PRs skipped), trigger time from label events, idempotent PR open with race handling, review-based approval, comments | `SimulatedGitHubClientContractTest` (the shared contract, 11 tests, real HTTP), `GitHubRestClientTest` (19) |
| Auth | GitHub App (JWT, installation token cached and refreshed) or token | `GitHubAppAuthTest` (7) |
| Labeler permission check (finding 8, first part) | Latest labeler needs write (configurable) | `GitHubRestClientTest.ignoresIssuesLabeledBySomeoneWithoutWriteAccess`, `theLatestLabelerCountsNotTheFirst` |
| Rate limits | ETag caching; rate-limit errors pause the ticket without spending retries | `GitHubRestClientTest` (rate-limit and ETag tests), `TicketPipelineTest.rateLimitedStepWaitsForTheResetWithoutSpendingARetry` |
| Live nightly | `LiveGitHubClientContractTest` + `factory-live.yml`, excluded from normal builds | **Passed 11/11 against real GitHub** (`thepredator07/factory-playground`, run 3, 2026-10-06); runs nightly from now on |
| Host-side push | Moved to M3: the branch comes from the sandbox | — |

Exit criteria: "passes on every push" is met, against the simulator instead of WireMock (decision 37). "Live test repo
nightly" is written but waiting on the repo. M2 counts as done once the first live run passes.

Bug found while testing: GitHub's "A pull request already exists" is in `errors[].message` of the 422 response, not in
`message`. The first version only read `message`, so the PR-creation race fell through to a failure. Fixed, and covered.

Mutation check: letting anyone trigger, dropping ETag reuse, dropping 422 details, ignoring review dismissals, and
counting rate limits as retries each make tests fail.

## M3 status: done

| Item | What changed | Proven by |
|------|--------------|-----------|
| `DockerSandboxRunner` | One container per attempt, idempotent `prepare`, `publishBranch`, `destroy`, `list`, `exec` | `DockerSandboxRunnerContractTest` (the shared contract plus reuse and stopped-container tests, 11), on real Docker |
| Isolation and no secrets | No network, user 1000, read-only root, all capabilities dropped, no-new-privileges, limits; token stays on the host | `DockerSandboxSecurityTest` (8): checked from inside the container and via `docker inspect` |
| Host-side push (moved from M2) | `HostGit`: bare clone per repo, bundle in, bundle out, push only `factory/*` | `DockerSandboxSecurityTest.theAgentsCommitsReachTheRemoteBranchAndMainIsUntouched`, `cannotPublishToMainEvenIfTheBranchIsRenamedInside` |
| Pipeline | Pushes the branch before opening the PR | `DockerSandboxPipelineTest.ticketReachesDoneThroughARealSandboxAndItsBranchIsPushed` |
| Crash recovery and janitor (finding 4) | Next worker reuses the sandbox; janitor removes sandboxes of finished or missing tickets | `DockerSandboxPipelineTest` (crash and janitor tests), `SandboxJanitorTest` |
| Egress allowlist | Not needed yet (no network at all); moved to M5 with the agent (decision 44) | — |

Exit criteria: contract and security tests pass on real Docker, and the GitHub Actions runners have Docker, so they
run in CI. "Killing the app mid-step leaves no orphan after the janitor runs" is covered by the janitor tests.

Mutation check: turning the network on, a writable root, keeping capabilities, a token in the environment,
recreating instead of reusing, the janitor ignoring finished tickets, and removing both branch-policy checks on publish
each make tests fail. Two of these first *survived*, which showed two weak tests. Effective capabilities are always
zero for a non-root user, so the test now checks the bounding set. A recreated container keeps its name, so the reuse
test now checks the container id and a file written inside. Both are fixed.

## M4 status: done

| Item | What changed | Proven by |
|------|--------------|-----------|
| `SandboxChecksRunner` | Runs the repo's check command in the ticket's sandbox (`sh -c`, working directory `/workspace/repo`, `CI=true`) on whatever the agent left in the working tree | `SandboxChecksRunnerContractTest` (shared contract, 3), `SandboxChecksRunnerTest` (11), on real Docker |
| Per-repo config | `.factory.yml` (`checks.command`, optional `checks.timeout`), **read on the host from the base branch**, so the agent can't weaken the checks by editing its copy. Fallback `factory.checks.default-command`; a repo's timeout is capped by `factory.checks.max-timeout` | `theAgentCannotWeakenTheChecksByEditingItsCopyOfTheConfig`, `aChangeToTheConfigOnMainIsUsedByTheNextRun`, `ChecksConfigTest` (11), `HostGitReadFileTest` (5) |
| Timeout | `timeout -k 10s <n>s` inside the sandbox stops the whole process group; the host waits 70 s longer as a backstop. A timeout counts as failed checks, so the agent sees it | `aHangingBuildIsStoppedAtTheTimeout...LeavesNoProcessBehind`, `aBuildThatIgnoresSigtermIsKilled` |
| Output truncation | `BoundedOutput` keeps the first 8 KB and the last ~55 KB in fixed memory, with a "bytes omitted" marker; the result is never over 64 K characters. Output starts with the command and ends with the exit code and duration | `floodingOutputIsCutToTheLimitKeepingTheStartAndTheEnd` (30 MB of output), `BoundedOutputTest` (6) |
| Repo misconfiguration | Missing or invalid `.factory.yml` fails the ticket **at once** with the reason (`UnrecoverableStepException`) instead of spending its retries | `aRepoWithoutACheckCommandFailsTheTicketAtOnceWithoutRetries`, `anInvalidConfigFailsTheTicketAtOnceWithTheReason` |
| Sandbox init process (found by the timeout test) | Sandboxes now run with Docker's `--init`. Without it, every process killed by a timeout stayed a zombie, because `sleep infinity` (PID 1) never reaps them, and they would pile up to the pids limit | Same timeout test (it asserts no `<defunct>` processes) |
| Wiring | `factory.integrations=real` now builds `SandboxChecksRunner`; only the agent (M5) is still missing | `IntegrationModeTest` |

**Exit criterion: "passes and fails correctly on a sample repo with a known failing test".** `SampleRepo` is a small
Python project with a real unit test and a known bug. `aRepoWithAKnownFailingTestFailsAndTheOutputNamesTheTest`
shows the checks fail and the output names `test_add_negative`. `checksRunOnTheAgentsWorkSoFixingTheBugMakesThemPass`
shows they pass once the code is fixed. `DockerChecksPipelineTest` runs the whole loop through the pipeline: a scripted
agent (standing in for M5) makes an unrelated change, the checks fail, the output goes back to it as feedback, it fixes
the bug, the checks pass, and the branch with the fix is pushed and the PR opened.

Mutation check: reading the config from the sandbox, dropping the `timeout` wrapper, dropping `-k`, unbounded output,
retrying unrecoverable failures, reading git output only after git exits, and turning off `--init` each make tests
fail. My first try at the git-output mutant was invalid (it threw an exception rather than reproducing the bug); the
proper one failed after the 30 s git timeout, as expected.

**Known limits, for M5 and the pilot:**
- The sandbox has no network, so checks can only use what is in the image or the repo. A real Java or Node repo needs
  an image with its toolchain and dependencies (or an offline cache in the repo), or a package mirror behind the M5
  egress proxy (decision 55).
- The agent can still change the *tests* themselves. Making failing checks pass by deleting a test is visible in the
  PR diff, and the human approval is the gate; protected paths could be added later.
- Checks don't watch for cancellation while running; a cancelled ticket notices after the checks finish (at most the
  checks timeout). Cancellation that stops running processes is part of M5 (finding 5).

## M6 status: done

| Item | What changed | Proven by |
|------|--------------|-----------|
| Dashboard sign-in (finding 8) | Spring Security, `FACTORY_SECURITY=github` (GitHub OAuth app; only logins in `FACTORY_ALLOWED_USERS`), `basic` (one admin account, form or HTTP Basic) or `none` (fake mode only). Real mode refuses to start without sign-in; bad settings (no allow list, short password, missing OAuth app) fail at startup with the fix in the message | `GitHubSignInTest` (2), `BasicSignInTest` (7), `SecurityPropertiesTest` (5), checked by hand with the packaged app |
| CSRF (finding 8) | Every dashboard POST needs the token (Thymeleaf adds it to forms). Exempt: the webhook (signature-checked) and the fake-mode `/api/fake/**` | `DashboardControllerTest.actionsWithoutACsrfTokenAreRefused`, `ticketPageFormsCarryTheCsrfToken`, `BasicSignInTest.signedInActionsStillNeedTheCsrfToken` |
| Audit | A dashboard cancel records who did it in the ticket history; a missing ticket gives 404 instead of 500 | `DashboardControllerTest`, `BasicSignInTest` |
| Webhooks (finding 12) | `POST /webhooks/github`, HMAC-SHA256 signature checked in constant time, 404 without a secret. `issues` events trigger a (coalesced) poll; reviews and PR closes wake the waiting ticket's job. Webhooks only speed things up: polling stays as the fallback and still applies every rule. Approval checks went from every 5 s to every minute | `GitHubWebhookTest` (6), `WebhookDisabledTest` (2) |
| Metrics (finding 10) | Prometheus on a separate management port (8081, not published by docker-compose): queue depth and tickets per state (read from the database, so every instance agrees), finished tickets by outcome, guardrail trips, lease losses, worker errors, step and agent-run timers, agent cost, tokens and turns | `ManagementPortTest`: real server, metrics only on the management port, counts match what happened, dashboard still needs sign-in |
| Logs (finding 10) | Ticket and job ids on every line written while a job runs (`[t:42 j:57]`), including the agent's thread; one line per state change | `ManagementPortTest` |
| Production defaults (finding 14) | Template caching on except with the `dev` profile; ticket list paged (50 per page); indexes for the dashboard's queries (V3) | `DashboardControllerTest.theTicketListIsPaged`, `MigrationTest` |
| Poller | Polls are serialized: a webhook-triggered poll and the scheduled one can't overlap and double-count a missing issue | Reasoned, not tested directly (decision 70) |

**Load test (exit criterion "one load test run at 1,000 tickets"):** `ThousandTicketsLoadTest`, on every build. 1,000
tickets through the whole pipeline (fakes) with 8 workers competing for the queue; one in ten has checks that fail
once. Result: all 1,000 DONE in **8.5 s (118 tickets/s)**, exactly one job per ticket, the expected number of agent
runs per ticket (1, or 2 after a failed check), 7,200 history rows as computed, no lease lost, no worker error.

**Exit criteria:** security tests pass; Prometheus shows queue depth, cost and outcomes; the load test passes.

Mutation check: CSRF off, the allow list skipped, everything open in `basic` mode, `none` allowed in real mode, the
webhook signature or repository not checked, actuator endpoints open on the main port, the finished-ticket counter,
the guardrail counter or the ticket id in the logs removed: each makes tests fail.

**Known limits:**
- One admin account in `basic` mode, and no roles: anyone allowed in can cancel any ticket.
- Webhooks cover one repository, like the rest of the factory (multi-repo is finding 15).
- The dashboard's stats still scan the tickets table on each view; fine to tens of thousands of tickets.

## M5 status: done

| Item | What changed | Proven by (no credential, every build) |
|------|--------------|-----------------------------------------|
| `ClaudeCodeAgentRunner` | `claude -p` headless in the sandbox: prompt (rules, issue, failed checks output) from a file, `--max-turns` and `--max-budget-usd` from the ticket's remaining budget, `--output-format stream-json` parsed as it streams, work committed on the ticket branch afterwards | `ClaudeCodeAgentRunnerTest` (8): the **real Claude Code CLI 2.1.291** in the real sandbox against a scripted model API |
| Credential never in the sandbox (decision 59) | `ModelApiProxy`: per-ticket nginx that swaps a placeholder credential for the real one. API key and subscription token both supported | `ModelApiProxyTest.modelCallsGoThrough...`, `inOauthMode...`; `codeTheAgentRunsSeesOnlyThePlaceholderCredential` (a tool call dumps every process environment) |
| Egress allowlist (moved here from M3) | Sandbox on its own internal network; the proxy is the only member, forwards `/v1/*` only, and runs only while the agent runs. Checks stop it again | `ModelApiProxyTest` (10): no direct route to the upstream, other hosts or the internet; 403 off `/v1/`; nothing reachable with the proxy stopped, including during checks |
| Cancellation (finding 5) | The agent runs in its own session; cancel, timeout and lease loss kill the session and process group | `cancellingKillsTheAgentAndEverythingItStartedWithinTenSeconds` (runner) and `DockerAgentPipelineTest.cancellingTheTicketStopsTheAgentWithinTenSeconds` (pipeline): under 10 s, including a background process the agent started |
| Feedback loop | Failed checks output goes into the next prompt | `DockerAgentPipelineTest.theRealCliFixesTheBugAfterTheChecksFailAndThePrHasTheFix`: real CLI, real checks, sample repo: fail → feedback → fix → PR |
| Turn limit | The CLI stops at `--max-turns`; the run counts as failed | `theTurnLimitStopsTheAgentAndCountsAsFailure` |
| Limited tools (decision 61) | `--tools Bash,Read,Edit,Write`, `--strict-mcp-config` | The model is offered exactly those four tools (checked in the recorded request) |
| Sandbox image | `sandbox/Dockerfile`: Claude Code's native binary, pinned and checked against npm's published SHA-512 | Built in CI before the tests; a wrong checksum fails the build (checked by hand) |
| Real mode complete | `RealIntegrationsConfig` wires the agent; startup fails clearly without `ANTHROPIC_API_KEY` or `CLAUDE_CODE_OAUTH_TOKEN`; an API key wins when both are set | `IntegrationModeTest` (6) |
| Usage parsing | `StreamJsonParser`, against output recorded from the real CLI | `StreamJsonParserTest` (4) |

**Exit criteria:**
- *Cancel stops spending within 10 s*: met with the real CLI (scripted model), in the runner and through the
  pipeline.
- *Guardrails trip on a deliberately oversized ticket* and *at least 6 of 10 fixture issues reach DONE within limits*:
  these need the real model. They are written (`AgentEvalTest`, `AgentGuardrailEvalTest`, tagged `agent-eval`) and run
  by the manual workflow **"Software Factory agent evaluation"** with the `CLAUDE_CODE_OAUTH_TOKEN` (or
  `ANTHROPIC_API_KEY`) secret. **Passed on the first live run** (below). The ten fixtures are
  checked on every build (`EvalFixturesTest`): each fails as written and passes with a reference fix, so a result
  says something about the agent. A dry run of the harness against the scripted model API produced the report as
  expected (0 of 10, since that model changes nothing).

### M5 live evaluation, run 1 (2026-10-06): passed

[Run 37520657685](https://github.com/thepredator07/thepredator07/actions/runs/37520657685), subscription token,
the CLI's default model, `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`, 2 min 11 s in total.

| Criterion | Result |
|-----------|--------|
| At least 6 of 10 fixture issues reach DONE within limits | Every fixture: the agent's first run succeeded (4 turns, 5 for `median`), the checks passed on the first try, no retries. The test's bar (DONE with the test file untouched, at least 6) passed; the per-fixture table is in the run's job summary. About 8 s and $0.016–0.019 per ticket |
| Guardrail on a deliberately oversized ticket | 3-turn limit: the CLI stopped at its turn limit (`error_max_turns`, 4 turns reported, $0.03), the ticket ended FAILED on the turn guardrail |
| Cancel stops spending within 10 s | Cancelled 15 s into a real run; the ticket was CANCELLED and the sandbox gone within the limit |

Costs are the CLI's list-price estimates (about $0.21 for the whole run); on a subscription token they count against
the plan's allowance instead. The fixtures are deliberately small; the pilot (M7) is where harder, real tickets get
measured.

Two bugs were found by the tests while building this:
- `setsid` forks when it is a process-group leader (as `docker exec` runs it), and without `--wait` it returned at
  once. The runner then stopped watching a CLI that carried on in the background; quick runs only passed because
  they finished within that window. The cancel test caught it; `--wait` fixes it (mutation-checked).
- Claude Code's Bash tool cuts long plain `sleep` commands short by itself, which made the first cancel test
  meaningless. The test now uses a Python sleep.

Mutation check: `setsid` without `--wait`, no kill after the run, the proxy left running, the proxy not injecting
the credential, a non-internal sandbox network, the proxy forwarding every path, all tools enabled, checks not
stopping the proxy, the agent's work not committed, and a no-change run counted as success each make tests fail.

**Known limits:**
- A process the agent deliberately detaches with its own `setsid` survives the kill until the sandbox is removed at
  the end of the ticket. It has no network (the proxy is stopped) but could still touch files before the checks.
- The prompt tells the agent not to edit tests, and the evaluation checks it, but nothing enforces it in production;
  the PR diff and the human approval are the gate.
- With a subscription token, `--max-budget-usd` and the cost guardrail use the CLI's list-price estimate; the real
  limit is the plan's usage allowance.
- A run cut off before its final result (cancel, timeout) reports its turns and tokens but not its cost.

### M2 live run, attempt 1 (2026-10-06): 8 of 11 passed

Against `thepredator07/factory-playground`, these passed on real GitHub: opening a PR, idempotent re-open, branch
refusals, approval (via merge) and comments. The three issue-listing tests failed. GitHub's labelled-issue listing lags
a few seconds behind writes: a just-created issue (#7) was missing, while a just-closed one (#5) was still listed.

Fixes (decisions 50–52):
- The contract's listing tests wait for consistency on real GitHub only.
- The poller needs two consecutive misses before cancelling, so a stale read can't cancel a healthy ticket.
- The simulator can lag the same way. With the lag on and the retry off it reproduces the same 3 failures; with the
  retry on it passes.

### M2 live run, attempt 2: 10 of 11 passed

All three listing tests now pass on real GitHub; the lag fix works. The last failure was in the live **test fixture**,
not the client. It prefixes the issues it creates with `[contract test] ` so they're easy to spot, but the contract then
expected the bare title. GitHub returned exactly what was created. Fix: the fixture declares its prefix
(`expectedTitle`), and the contract compares against that.

### M2 live run, attempt 3: 11 of 11 passed

[Run 3](https://github.com/thepredator07/thepredator07/actions/runs/37506047522): `Tests run: 11, Failures: 0, Errors: 0,
Skipped: 0` against real GitHub. **M2 is done.** The workflow keeps running nightly at 03:17 UTC.
