package com.ticketfactory.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.FactoryApplication;
import com.ticketfactory.fake.FakeAgentRunner;
import com.ticketfactory.intake.TicketIntake;
import com.ticketfactory.queue.JobQueue;
import com.ticketfactory.queue.Worker;
import com.ticketfactory.queue.WorkerPool;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.TicketState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Two complete app instances (two Spring contexts, each with its own workers, fakes and agent executor) share one
 * PostgreSQL. Every agent run takes 2.5x the lease, and both instances run aggressive lease reapers. Before M0 this
 * double-processed tickets; now every ticket must get exactly one agent run.
 */
@TestPropertySource(properties = {
        "factory.worker.lease-timeout=PT0.3S",
        "factory.guardrails.hard-timeout=PT2M"})
class TwoInstancesTest extends AbstractIntegrationTest {

    private static final int TICKETS = 12;
    private static final Duration LEASE = Duration.ofMillis(300);

    @Autowired PostgreSQLContainer<?> postgres;
    @Autowired TicketIntake intake;
    @Autowired TicketRepository tickets;
    @Autowired JobQueue queue;
    @Autowired WorkerPool poolA;
    @Autowired FakeAgentRunner agentA;

    private ConfigurableApplicationContext instanceB;

    @BeforeEach
    void startSecondInstance() {
        agentA.reset();
        instanceB = new SpringApplicationBuilder(FactoryApplication.class)
                .profiles("test")
                // Command-line args, so they win over application.yml (builder properties would not).
                .run("--spring.datasource.url=" + postgres.getJdbcUrl(),
                        "--spring.datasource.username=" + postgres.getUsername(),
                        "--spring.datasource.password=" + postgres.getPassword(),
                        "--server.port=0",
                        "--factory.worker.lease-timeout=PT0.3S",
                        "--factory.guardrails.hard-timeout=PT2M");
    }

    @AfterEach
    void stopSecondInstance() {
        instanceB.close();
    }

    @Test
    void twoInstancesNeverRunTheSameTicketTwice() throws Exception {
        for (int i = 1; i <= TICKETS; i++) {
            intake.accept("example-org/example-repo", i, "Slow ticket " + i, "fake-agent-delay: PT0.75S");
        }
        FakeAgentRunner agentB = instanceB.getBean(FakeAgentRunner.class);
        JobQueue queueB = instanceB.getBean(JobQueue.class);
        WorkerPool poolB = instanceB.getBean(WorkerPool.class);

        List<Worker> workers = List.of(poolA.newWorker("a1"), poolA.newWorker("a2"), poolA.newWorker("a3"),
                poolB.newWorker("b1"), poolB.newWorker("b2"), poolB.newWorker("b3"));
        AtomicBoolean done = new AtomicBoolean();
        AtomicInteger reaped = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // One aggressive reaper per instance, as WorkerPool would run in production (but much more often).
            for (JobQueue q : List.of(queue, queueB)) {
                pool.submit(() -> {
                    while (!done.get()) {
                        reaped.addAndGet(q.releaseExpiredLeases(LEASE));
                        Thread.sleep(25);
                    }
                    return null;
                });
            }
            List<Future<?>> runs = new ArrayList<>();
            for (Worker w : workers) {
                runs.add(pool.submit(() -> {
                    long deadline = System.currentTimeMillis() + 60_000;
                    while (System.currentTimeMillis() < deadline && unfinished() > 0) {
                        if (!w.runOnce()) {
                            Thread.sleep(20);
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : runs) {
                f.get(90, TimeUnit.SECONDS);
            }
            done.set(true);
        }

        List<Ticket> all = tickets.findAll(null, 100);
        assertThat(all).hasSize(TICKETS).allSatisfy(t -> assertThat(t.state()).isEqualTo(TicketState.DONE));
        for (Ticket t : all) {
            int runs = agentA.callCount(t.id()) + agentB.callCount(t.id());
            assertThat(runs).as("agent runs for ticket %d", t.id()).isEqualTo(1);
            assertThat(t.turns()).as("usage recorded once for ticket %d", t.id()).isEqualTo(6);
        }
        assertThat(agentA.callCountTotal()).as("instance A did work").isPositive();
        assertThat(agentB.callCountTotal()).as("instance B did work").isPositive();
        assertThat(reaped.get()).as("no live job was ever reclaimed").isZero();
    }

    private long unfinished() {
        return tickets.findAll(null, 100).stream().filter(t -> !t.state().isTerminal()).count();
    }
}
