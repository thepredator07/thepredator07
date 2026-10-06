package com.ticketfactory.pipeline;

import static com.ticketfactory.ticket.TicketState.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.integration.SandboxRunner;
import com.ticketfactory.integration.TicketContext;
import com.ticketfactory.integration.docker.DockerSandboxFixture;
import com.ticketfactory.integration.docker.DockerSandboxRunner;
import com.ticketfactory.intake.GitHubPoller;
import com.ticketfactory.queue.JobQueue;
import com.ticketfactory.queue.WorkerPool;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.TicketService;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/**
 * The pipeline with a real Docker sandbox (fake GitHub, agent and checks). The "remote" is a local bare repo with
 * the same name as the factory's configured repo.
 */
@Import(DockerSandboxPipelineTest.DockerSandboxConfig.class)
@TestPropertySource(properties = "factory.repo=" + DockerSandboxFixture.REPO)
class DockerSandboxPipelineTest extends AbstractIntegrationTest {

    static final DockerSandboxFixture FX;

    static {
        try {
            FX = new DockerSandboxFixture();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class DockerSandboxConfig {
        @Bean
        @Primary
        SandboxRunner dockerSandbox() {
            return FX.runner;
        }
    }

    @AfterAll
    static void cleanUp() throws IOException {
        FX.close();
    }

    @Autowired FakeGitHubClient github;
    @Autowired GitHubPoller poller;
    @Autowired WorkerPool workers;
    @Autowired TicketRepository tickets;
    @Autowired TicketService ticketService;
    @Autowired JobQueue queue;
    @Autowired SandboxJanitor janitor;
    @Autowired FactoryProperties props;

    @BeforeEach
    void reset() {
        github.reset();
        // Ticket ids restart at 1 after each truncate; keep them clear of other runs' containers.
        jdbc.sql("ALTER SEQUENCE tickets_id_seq RESTART WITH " + FX.id(100)).update();
    }

    private long submit(int issue, String body) {
        github.addIssue(props.repo(), issue, "Docker ticket " + issue, body, props.triggerLabel());
        poller.pollOnce();
        return tickets.attemptsFor(props.repo(), issue).getFirst().id();
    }

    @Test
    void ticketReachesDoneThroughARealSandboxAndItsBranchIsPushed() {
        long id = submit(1, "");

        workers.newWorker("docker").drain(50);

        Ticket t = tickets.get(id);
        assertThat(t.state()).isEqualTo(DONE);
        assertThat(t.sandboxId()).isEqualTo(DockerSandboxRunner.containerName(id));
        assertThat(FX.remoteHead("factory/" + id)).as("branch pushed before the PR was opened").isNotNull();
        assertThat(FX.runner.inspect(t.sandboxId())).as("container removed at the end").isNull();
    }

    @Test
    void afterACrashMidRunTheNextWorkerReusesTheSandboxAndCleansUp() {
        long id = submit(2, "");
        // A previous worker created the sandbox, then the app died: job still RUNNING, ticket still RECEIVED.
        var job = queue.claim("dead-worker").orElseThrow();
        FX.runner.prepare(new TicketContext(id, props.repo(), 2, "x", "", "factory/" + id));
        jdbc.sql("UPDATE jobs SET locked_at = now() - interval '1 hour' WHERE id = :id").param("id", job.id()).update();

        assertThat(queue.releaseExpiredLeases(Duration.ofMinutes(10))).isEqualTo(1);
        workers.newWorker("survivor").runOnce(); // runs the whole ticket

        Ticket t = tickets.get(id);
        assertThat(t.state()).isEqualTo(DONE);
        assertThat(FX.runner.inspect(DockerSandboxRunner.containerName(id))).isNull();
        assertThat(FX.remoteHead("factory/" + id)).isNotNull();
    }

    @Test
    void janitorRemovesTheSandboxOfATicketThatEndedWithoutCleanup() {
        long running = submit(3, "");
        long ended = submit(4, "");
        String keep = FX.runner.prepare(new TicketContext(running, props.repo(), 3, "x", "", "factory/" + running)).id();
        String orphan = FX.runner.prepare(new TicketContext(ended, props.repo(), 4, "x", "", "factory/" + ended)).id();
        // The app was killed after this ticket was marked FAILED but before its sandbox was destroyed.
        ticketService.transition(ended, RECEIVED, FAILED, "worker died");

        int removed = janitor.sweep();

        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(FX.runner.inspect(orphan)).isNull();
        assertThat(FX.runner.inspect(keep)).as("a running ticket keeps its sandbox").isNotNull();
        FX.runner.destroy(keep);
    }
}
