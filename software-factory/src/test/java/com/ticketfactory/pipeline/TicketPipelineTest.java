package com.ticketfactory.pipeline;

import static com.ticketfactory.ticket.TicketState.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.fake.FakeBehavior;
import com.ticketfactory.integration.GitHubClient.PrStatus;
import com.ticketfactory.queue.Job;
import com.ticketfactory.queue.JobStatus;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TicketPipelineTest extends PipelineTestSupport {

    @Autowired
    TicketService ticketService;

    @Autowired
    TicketPipeline pipeline;

    @Test
    void happyPathVisitsEveryStateAndRecordsUsage() {
        long id = submit(1, "Add CSV export", "Users want CSV.");

        runUntilIdle();

        Ticket t = ticket(id);
        assertThat(t.state()).isEqualTo(DONE);
        assertThat(states(id)).containsExactly(RECEIVED, SANDBOX_READY, CODING, CHECKS, PR_OPENED,
                AWAITING_APPROVAL, DONE);
        assertThat(t.branchName()).isEqualTo("factory/" + id);
        assertThat(t.turns()).isEqualTo(6);
        assertThat(t.tokensInput()).isEqualTo(24_000);
        assertThat(t.tokensOutput()).isEqualTo(4_800);
        assertThat(t.costUsd()).isEqualByComparingTo("0.12");
        assertThat(t.retries()).isZero();
        assertThat(t.finishedAt()).isNotNull();
        assertThat(t.durationMs()).isNotNull();
        assertThat(t.prNumber()).isNotNull();

        assertThat(github.openedPullRequests()).singleElement().satisfies(pr -> {
            assertThat(pr.pr().head()).isEqualTo("factory/" + id);
            assertThat(pr.pr().base()).isEqualTo("main");
            assertThat(pr.body()).contains("Closes #1");
        });
        assertThat(github.comments()).singleElement().asString().contains("pull/");
        assertThat(sandbox.isLive(t.sandboxId())).as("sandbox destroyed at the end").isFalse();
        assertThat(queue.findByTicket(id)).singleElement().extracting(Job::status).isEqualTo(JobStatus.DONE);
    }

    @Test
    void sandboxFailThenSucceedIsRetried() {
        sandbox.setDefaultBehavior(FakeBehavior.failThenSucceed(2));
        long id = submit(1, "Flaky sandbox", "");

        runUntilIdle();

        Ticket t = ticket(id);
        assertThat(t.state()).isEqualTo(DONE);
        assertThat(t.retries()).isEqualTo(2);
        assertThat(sandbox.callCount(id)).isEqualTo(3);
    }

    @Test
    void sandboxThatAlwaysFailsEndsInFailedAfterMaxRetries() {
        long id = submit(1, "Broken sandbox", "fake-sandbox: fail");

        runUntilIdle();

        Ticket t = ticket(id);
        assertThat(t.state()).isEqualTo(FAILED);
        assertThat(states(id)).containsExactly(RECEIVED, FAILED);
        assertThat(t.retries()).isEqualTo(props.guardrails().maxRetries() + 1);
        assertThat(sandbox.callCount(id)).isEqualTo(props.guardrails().maxRetries() + 1);
        assertThat(t.failureReason()).contains("RECEIVED failed after 3 retries").contains("simulated");
        assertThat(queue.findByTicket(id)).singleElement().extracting(Job::status).isEqualTo(JobStatus.DONE);
    }

    @Test
    void failingChecksLoopBackToCodingWithFeedback() {
        long id = submit(1, "Fix date parsing", "fake-checks: fail-then-succeed 1");

        runUntilIdle();

        Ticket t = ticket(id);
        assertThat(t.state()).isEqualTo(DONE);
        assertThat(states(id)).containsExactly(RECEIVED, SANDBOX_READY, CODING, CHECKS, CODING, CHECKS,
                PR_OPENED, AWAITING_APPROVAL, DONE);
        assertThat(t.retries()).isEqualTo(1);
        assertThat(agent.callCount(id)).isEqualTo(2);
        assertThat(t.lastFeedback()).contains("Failures: 1");
        assertThat(t.turns()).isEqualTo(12);
        assertThat(t.costUsd()).isEqualByComparingTo("0.24");
    }

    @Test
    void checksThatNeverPassFailTheTicket() {
        long id = submit(1, "Impossible", "fake-checks: fail");

        runUntilIdle();

        Ticket t = ticket(id);
        assertThat(t.state()).isEqualTo(FAILED);
        assertThat(t.failureReason()).contains("Checks still failing");
        assertThat(agent.callCount(id)).isEqualTo(props.guardrails().maxRetries() + 1);
        assertThat(github.openedPullRequests()).isEmpty();
    }

    @Test
    void agentThatAlwaysFailsFailsTheTicketButUsageIsStillRecorded() {
        long id = submit(1, "Agent gives up", "fake-agent: fail");

        runUntilIdle();

        Ticket t = ticket(id);
        assertThat(t.state()).isEqualTo(FAILED);
        assertThat(t.failureReason()).contains("CODING failed").contains("could not produce");
        int runs = props.guardrails().maxRetries() + 1;
        assertThat(t.turns()).isEqualTo(6 * runs);
        assertThat(t.costUsd()).isEqualByComparingTo("0.48");
    }

    @Test
    void costGuardrailFailsImmediatelyWithoutRetry() {
        long id = submit(1, "Expensive", "fake-agent-cost: 5.00");

        runUntilIdle();

        Ticket t = ticket(id);
        assertThat(t.state()).isEqualTo(FAILED);
        assertThat(t.failureReason()).isEqualTo("guardrail: cost $5.00 exceeded limit $2.00");
        assertThat(agent.callCount(id)).isEqualTo(1);
        assertThat(t.retries()).isZero();
        assertThat(t.costUsd()).isEqualByComparingTo("5.00");
        assertThat(sandbox.isLive(t.sandboxId())).isFalse();
    }

    @Test
    void costGuardrailAccumulatesAcrossRetries() {
        // 0.80 per run is under the 2.00 limit, but three runs (two check failures) are not.
        long id = submit(1, "Death by retries", "fake-agent-cost: 0.80\nfake-checks: fail");

        runUntilIdle();

        Ticket t = ticket(id);
        assertThat(t.state()).isEqualTo(FAILED);
        assertThat(t.failureReason()).contains("guardrail: cost $2.40");
        assertThat(agent.callCount(id)).isEqualTo(3);
    }

    @Test
    void turnsGuardrailFailsTheTicket() {
        long id = submit(1, "Chatty agent", "fake-agent-turns: 50");

        runUntilIdle();

        Ticket t = ticket(id);
        assertThat(t.state()).isEqualTo(FAILED);
        assertThat(t.failureReason()).isEqualTo("guardrail: turns 50 exceeded limit 40");
    }

    @Test
    void pendingApprovalParksTheJobUntilApproved() {
        long id = submit(1, "Needs review", "fake-approval: pending");

        runUntilIdle();
        Ticket t = ticket(id);
        assertThat(t.state()).isEqualTo(AWAITING_APPROVAL);
        Job parked = queue.findActiveForTicket(id).orElseThrow();
        assertThat(parked.status()).isEqualTo(JobStatus.PENDING);
        assertThat(parked.lastError()).isEqualTo("waiting for PR approval");

        github.setPullRequestStatus(t.prNumber(), PrStatus.APPROVED);
        wakeUpJobs();
        runUntilIdle();

        assertThat(ticket(id).state()).isEqualTo(DONE);
    }

    @Test
    void closedPrCancelsTheTicket() {
        long id = submit(1, "Rejected", "fake-approval: closed");

        runUntilIdle();

        assertThat(ticket(id).state()).isEqualTo(CANCELLED);
        assertThat(tickets.history(id).getLast().reason()).contains("closed without merging");
    }

    @Test
    void githubFailThenSucceedOpensThePrOnRetry() {
        github.setDefaultBehavior(FakeBehavior.failThenSucceed(1));
        long id = submit(1, "API hiccup", "");

        runUntilIdle();

        Ticket t = ticket(id);
        assertThat(t.state()).isEqualTo(DONE);
        assertThat(t.retries()).isEqualTo(1);
        assertThat(github.openedPullRequests()).hasSize(1);
    }

    @Test
    void cancelledTicketIsNotWorkedOnAndJobCompletes() {
        long id = submit(1, "Never mind", "");
        ticketService.cancel(id, "Cancelled from dashboard");

        runUntilIdle();

        assertThat(ticket(id).state()).isEqualTo(CANCELLED);
        assertThat(sandbox.callCount(id)).isZero();
        assertThat(queue.findByTicket(id)).singleElement().extracting(Job::status).isEqualTo(JobStatus.DONE);
    }

    @Test
    void cancellingWhileAwaitingApprovalStopsTheJob() {
        long id = submit(1, "Waiting", "fake-approval: pending");
        runUntilIdle();
        ticketService.cancel(id, "user");
        wakeUpJobs();

        runUntilIdle();

        assertThat(ticket(id).state()).isEqualTo(CANCELLED);
        assertThat(queue.findActiveForTicket(id)).isEmpty();
        assertThat(sandbox.isLive(ticket(id).sandboxId())).isFalse();
    }

    @Test
    void manyTicketsInParallelAllFinish() throws Exception {
        for (int i = 1; i <= 20; i++) {
            submit(i, "Ticket " + i, i % 4 == 0 ? "fake-agent: fail" : "");
        }
        var w1 = workers.newWorker("a");
        var w2 = workers.newWorker("b");
        var w3 = workers.newWorker("c");
        try (var pool = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            pool.submit(() -> w1.drain(500));
            pool.submit(() -> w2.drain(500));
            pool.submit(() -> w3.drain(500));
        }
        var all = tickets.findAll(null, 100);
        assertThat(all).hasSize(20);
        assertThat(all).filteredOn(t -> t.state() == DONE).hasSize(15);
        assertThat(all).filteredOn(t -> t.state() == FAILED).hasSize(5);
        assertThat(github.openedPullRequests()).hasSize(15);
    }

    // ---- M0: long agent runs react to cancellation and lease loss ----

    @Test
    void cancellingDuringALongAgentRunStopsWaitingForIt() throws Exception {
        long id = submit(1, "Slow agent", "fake-agent-delay: PT20S");
        try (var bg = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var run = bg.submit(() -> worker.runOnce());
            long until = System.currentTimeMillis() + 10_000;
            while (ticket(id).state() != CODING && System.currentTimeMillis() < until) {
                Thread.sleep(20);
            }
            assertThat(ticket(id).state()).isEqualTo(CODING);
            long cancelledAt = System.nanoTime();
            ticketService.cancel(id, "user");

            run.get(5, java.util.concurrent.TimeUnit.SECONDS);
            long stoppedAfterMs = (System.nanoTime() - cancelledAt) / 1_000_000;
            assertThat(stoppedAfterMs).as("worker stopped waiting soon after cancel").isLessThan(2_000);
        }
        assertThat(ticket(id).state()).isEqualTo(CANCELLED);
        assertThat(queue.findByTicket(id)).singleElement().extracting(Job::status).isEqualTo(JobStatus.DONE);
        assertThat(sandbox.isLive(ticket(id).sandboxId())).isFalse();
    }

    @Test
    void losingTheLeaseDuringAnAgentRunAbandonsWithoutTouchingTheTicket() {
        long id = submit(1, "Slow agent", "fake-agent-delay: PT20S");
        Job job = queue.claim("w").orElseThrow();
        long leaseLostAt = System.currentTimeMillis() + 500;

        long start = System.nanoTime();
        var outcome = pipeline.handle(job, () -> System.currentTimeMillis() < leaseLostAt);
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(outcome).isInstanceOf(com.ticketfactory.queue.JobOutcome.Abandon.class);
        assertThat(tookMs).isLessThan(3_000);
        Ticket t = ticket(id);
        assertThat(t.state()).as("left for the new owner to continue").isEqualTo(CODING);
        assertThat(t.turns()).as("no usage recorded by the abandoned run").isZero();
    }

    // ---- M1: crash between an external side effect and the DB write ----

    @Test
    void sandboxLeftByACrashedRunIsReusedNotDuplicated() {
        long id = submit(1, "Crash after sandbox", "");
        // A previous run created the sandbox, then died before saving its id.
        sandbox.prepare(new com.ticketfactory.integration.TicketContext(id, props.repo(), 1, "x", "",
                com.ticketfactory.integration.BranchPolicy.branchFor(id)));
        assertThat(sandbox.createdCount()).isEqualTo(1);

        runUntilIdle();

        assertThat(ticket(id).state()).isEqualTo(DONE);
        assertThat(sandbox.createdCount()).as("no second sandbox").isEqualTo(1);
        assertThat(sandbox.liveCount()).as("nothing left over").isZero();
    }

    @Test
    void prOpenedByACrashedRunIsFoundNotDuplicated() {
        long id = submit(1, "Crash after PR", "");
        // A previous run opened the PR on GitHub, then died before saving its number.
        var orphan = github.openPullRequest(new com.ticketfactory.integration.GitHubClient.PullRequestRequest(
                props.repo(), 1, "factory/" + id, "main", "t", "Closes #1"));

        runUntilIdle();

        Ticket t = ticket(id);
        assertThat(t.state()).isEqualTo(DONE);
        assertThat(github.openedPullRequests()).as("no duplicate PR").hasSize(1);
        assertThat(t.prNumber()).isEqualTo(orphan.number());
    }

    // ---- M2: rate limits wait for the reset and don't spend retries ----

    @Test
    void rateLimitedStepWaitsForTheResetWithoutSpendingARetry() {
        github.rateLimitNextPullRequests(5, java.time.Duration.ofMinutes(7)); // more than max-retries
        long id = submit(1, "Busy API", "");

        runUntilIdle();

        Ticket t = ticket(id);
        assertThat(t.state()).as("parked, not failed").isEqualTo(CHECKS);
        assertThat(t.retries()).isZero();
        Job parked = queue.findActiveForTicket(id).orElseThrow();
        assertThat(parked.lastError()).startsWith("rate limited:");
        assertThat(parked.runAfter()).isAfter(java.time.Instant.now().plus(java.time.Duration.ofMinutes(6)));

        for (int i = 0; i < 5; i++) { // each wake-up hits the limit once more, then it clears
            wakeUpJobs();
            runUntilIdle();
        }
        assertThat(ticket(id).state()).isEqualTo(DONE);
        assertThat(ticket(id).retries()).isZero();
    }
}
