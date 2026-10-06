package com.ticketfactory.e2e;

import static com.ticketfactory.ticket.TicketState.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.fake.FakeAgentRunner;
import com.ticketfactory.fake.FakeChecksRunner;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.fake.FakeSandboxRunner;
import com.ticketfactory.intake.GitHubPoller;
import com.ticketfactory.queue.WorkerPool;
import com.ticketfactory.stats.Stats;
import com.ticketfactory.stats.StatsService;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.Transition;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The whole factory, outside in: issues appear on (fake) GitHub, the poller turns them into tickets, a worker
 * drives them through the pipeline, and the stats reflect the outcomes.
 */
class FactoryEndToEndTest extends AbstractIntegrationTest {

    @Autowired FakeGitHubClient github;
    @Autowired FakeSandboxRunner sandbox;
    @Autowired FakeAgentRunner agent;
    @Autowired FakeChecksRunner checks;
    @Autowired GitHubPoller poller;
    @Autowired WorkerPool workers;
    @Autowired TicketRepository tickets;
    @Autowired StatsService statsService;
    @Autowired FactoryProperties props;

    @BeforeEach
    void reset() {
        github.reset();
        sandbox.reset();
        agent.reset();
        checks.reset();
    }

    private Ticket byIssue(int issue) {
        return tickets.findAll(null, 100).stream().filter(t -> t.issueNumber() == issue).findFirst().orElseThrow();
    }

    @Test
    void oneIssueReachesDoneAnotherFailsAndStatsReflectBoth() {
        github.addIssue(props.repo(), 101, "Add pagination to /orders", "The list endpoint returns everything.",
                props.triggerLabel());
        github.addIssue(props.repo(), 102, "Rewrite billing in Rust", "fake-agent: fail", props.triggerLabel());
        github.addIssue(props.repo(), 103, "Not for the factory", "", "question");

        assertThat(poller.pollOnce()).isEqualTo(2);
        int processed = workers.newWorker("e2e").drain(100);
        assertThat(processed).isPositive();

        // Issue 101: every state, in order, to DONE.
        Ticket done = byIssue(101);
        assertThat(done.state()).isEqualTo(DONE);
        assertThat(tickets.history(done.id())).extracting(Transition::toState).containsExactly(
                RECEIVED, SANDBOX_READY, CODING, CHECKS, PR_OPENED, AWAITING_APPROVAL, DONE);
        assertThat(tickets.history(done.id())).allSatisfy(tr -> assertThat(tr.createdAt()).isNotNull());
        assertThat(done.prUrl()).contains("/pull/");
        assertThat(github.openedPullRequests()).singleElement()
                .satisfies(pr -> assertThat(pr.pr().head()).isEqualTo("factory/" + done.id()));

        // Issue 102: agent never succeeds, ticket ends FAILED after max retries.
        Ticket failed = byIssue(102);
        assertThat(failed.state()).isEqualTo(FAILED);
        assertThat(tickets.history(failed.id())).extracting(Transition::toState)
                .containsExactly(RECEIVED, SANDBOX_READY, CODING, FAILED);
        assertThat(failed.retries()).isEqualTo(props.guardrails().maxRetries() + 1);
        assertThat(failed.failureReason()).contains("could not produce a working change");

        // Per-ticket records.
        for (Ticket t : new Ticket[]{done, failed}) {
            assertThat(t.durationMs()).isNotNull();
            assertThat(t.totalTokens()).isPositive();
            assertThat(t.costUsd()).isPositive();
            assertThat(t.finishedAt()).isNotNull();
        }

        // Stats reflect both.
        Stats stats = statsService.compute();
        assertThat(stats.total()).isEqualTo(2);
        assertThat(stats.done()).isEqualTo(1);
        assertThat(stats.failed()).isEqualTo(1);
        assertThat(stats.active()).isZero();
        assertThat(stats.successRate()).isEqualTo(0.5);
        BigDecimal expectedTotal = done.costUsd().add(failed.costUsd());
        assertThat(stats.totalCostUsd()).isEqualByComparingTo(expectedTotal);
        assertThat(stats.avgCostUsd()).isEqualByComparingTo(expectedTotal.divide(BigDecimal.TWO));
        assertThat(stats.avgDurationMs()).isNotNull()
                .isEqualTo(Math.round((done.durationMs() + failed.durationMs()) / 2.0));
        assertThat(stats.totalTokens()).isEqualTo(done.totalTokens() + failed.totalTokens());
        assertThat(stats.totalRetries()).isEqualTo(failed.retries());
        assertThat(stats.byState().get(DONE)).isEqualTo(1);
        assertThat(stats.byState().get(FAILED)).isEqualTo(1);
    }

    @Test
    void statsAreEmptyButValidWithNoTickets() {
        Stats stats = statsService.compute();
        assertThat(stats.total()).isZero();
        assertThat(stats.successRate()).isNull();
        assertThat(stats.avgDurationMs()).isNull();
        assertThat(stats.avgCostUsd()).isNull();
        assertThat(stats.totalCostUsd()).isEqualByComparingTo("0");
    }
}
