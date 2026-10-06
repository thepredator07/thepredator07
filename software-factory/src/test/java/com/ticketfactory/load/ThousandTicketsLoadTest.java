package com.ticketfactory.load;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.fake.FakeAgentRunner;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.intake.GitHubPoller;
import com.ticketfactory.queue.WorkerPool;
import com.ticketfactory.ticket.TicketState;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.test.context.TestPropertySource;

/**
 * M6 load test: 1,000 tickets through the whole pipeline (fakes) with 8 workers competing for the queue. One ticket
 * in ten has checks that fail once, so the retry loop runs under load too. Every ticket must end DONE with exactly the
 * expected number of agent runs and state changes, every job DONE, and no lease lost.
 */
@AutoConfigureObservability
@TestPropertySource(properties = "factory.fakes.github.auto-approve=true")
class ThousandTicketsLoadTest extends AbstractIntegrationTest {

    static final int TICKETS = 1000;
    static final int WORKERS = 8;

    @Autowired FakeGitHubClient github;
    @Autowired FakeAgentRunner agent;
    @Autowired GitHubPoller poller;
    @Autowired WorkerPool workers;
    @Autowired FactoryProperties props;
    @Autowired MeterRegistry metrics;

    @Test
    void aThousandTicketsEachRunExactlyAsOftenAsTheyShould() throws Exception {
        github.reset();
        agent.reset();
        for (int n = 1; n <= TICKETS; n++) {
            github.addIssue(props.repo(), n, "Load ticket " + n, n % 10 == 0 ? "fake-checks: fail-then-succeed 1" : "",
                    props.triggerLabel());
        }
        long started = System.nanoTime();
        assertThat(poller.pollOnce()).isEqualTo(TICKETS);
        long polled = System.nanoTime();

        try (var pool = Executors.newFixedThreadPool(WORKERS)) {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int w = 0; w < WORKERS; w++) {
                String name = "load-" + w;
                runs.add(pool.submit(() -> workers.newWorker(name).drain(Integer.MAX_VALUE)));
            }
            int jobsRun = 0;
            for (Future<Integer> r : runs) {
                jobsRun += r.get();
            }
            assertThat(jobsRun).as("one job per ticket, each run once (the retry loop stays inside the job)")
                    .isEqualTo(TICKETS);
        }
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        Map<String, Long> byState = jdbc.sql("SELECT state, count(*) AS n FROM tickets GROUP BY state")
                .query((rs, i) -> Map.entry(rs.getString("state"), rs.getLong("n"))).list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(byState).containsExactly(Map.entry(TicketState.DONE.name(), (long) TICKETS));

        assertThat(jdbc.sql("SELECT count(*) FROM jobs WHERE status <> 'DONE'").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM jobs").query(Long.class).single()).isEqualTo(TICKETS);

        // Agent runs: 1 per ticket, 2 for the tickets whose checks failed once.
        List<Long> ids = jdbc.sql("SELECT id FROM tickets ORDER BY issue_number").query(Long.class).list();
        int expectedRuns = 0;
        for (int i = 0; i < ids.size(); i++) {
            int expected = (i + 1) % 10 == 0 ? 2 : 1;
            assertThat(agent.callCount(ids.get(i))).as("agent runs for issue " + (i + 1)).isEqualTo(expected);
            expectedRuns += expected;
        }
        assertThat(agent.callCountTotal()).isEqualTo(expectedRuns);

        // History rows: creation plus 6 steps on the happy path; a checks retry adds CHECKS -> CODING -> CHECKS.
        long transitions = jdbc.sql("SELECT count(*) FROM ticket_transitions").query(Long.class).single();
        assertThat(transitions).isEqualTo(TICKETS * 7L + (TICKETS / 10) * 2L);

        assertThat(metrics.find("factory.lease.losses").counter()).as("no lease lost").isNull();
        assertThat(metrics.find("factory.worker.errors").counter()).as("no worker errors").isNull();

        System.out.printf("Load test: %d tickets, %d workers, polled in %d ms, all done in %.1f s (%.0f tickets/s)%n",
                TICKETS, WORKERS, Duration.ofNanos(polled - started).toMillis(), took.toMillis() / 1000.0,
                TICKETS / (took.toMillis() / 1000.0));
        assertThat(took).as("well within a CI run").isLessThan(Duration.ofMinutes(3));
    }
}
