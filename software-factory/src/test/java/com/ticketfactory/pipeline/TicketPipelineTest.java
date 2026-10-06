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
}
