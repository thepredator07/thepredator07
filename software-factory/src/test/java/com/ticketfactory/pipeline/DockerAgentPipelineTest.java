package com.ticketfactory.pipeline;

import static com.ticketfactory.ticket.TicketState.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.integration.AgentRunner;
import com.ticketfactory.integration.ChecksRunner;
import com.ticketfactory.integration.SandboxRunner;
import com.ticketfactory.integration.agent.AgentTestEnvironment;
import com.ticketfactory.integration.checks.ChecksProperties;
import com.ticketfactory.integration.checks.SampleRepo;
import com.ticketfactory.integration.checks.SandboxChecksRunner;
import com.ticketfactory.integration.docker.DockerSandboxFixture;
import com.ticketfactory.integration.docker.HostGit;
import com.ticketfactory.integration.docker.ModelApiProxy;
import com.ticketfactory.intake.GitHubPoller;
import com.ticketfactory.queue.WorkerPool;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.TicketService;
import com.ticketfactory.ticket.Transition;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
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
 * The whole pipeline with everything real except GitHub and the model: Docker sandbox, model proxy, the real Claude
 * Code CLI, real checks on the sample repo, host-side push. The scripted model fixes the bug once the checks output
 * names the failing test, like a real agent would.
 */
@Import(DockerAgentPipelineTest.Config.class)
@TestPropertySource(properties = "factory.repo=" + DockerSandboxFixture.REPO)
class DockerAgentPipelineTest extends AbstractIntegrationTest {

    static final AgentTestEnvironment ENV;

    static {
        try {
            ENV = new AgentTestEnvironment(ModelApiProxy.Mode.API_KEY);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        SandboxRunner dockerSandbox() {
            return ENV.runner;
        }

        @Bean
        @Primary
        AgentRunner claudeCode() {
            return ENV.agent();
        }

        @Bean
        @Primary
        ChecksRunner sandboxChecks(FactoryProperties props) {
            HostGit git = new HostGit(ENV.fx.root.resolve("checks-host"),
                    "file://" + ENV.fx.root.resolve("remotes") + "/{repo}.git", null, Duration.ofMinutes(2));
            return new SandboxChecksRunner(ENV.runner, git,
                    new ChecksProperties(".factory.yml", "", Duration.ofMinutes(5), Duration.ofMinutes(30)),
                    props.baseBranch());
        }
    }

    @AfterAll
    static void cleanUp() throws IOException {
        ENV.close();
    }

    @Autowired FakeGitHubClient github;
    @Autowired GitHubPoller poller;
    @Autowired WorkerPool workers;
    @Autowired TicketRepository tickets;
    @Autowired TicketService ticketService;
    @Autowired FactoryProperties props;

    @BeforeEach
    void reset() {
        github.reset();
        jdbc.sql("ALTER SEQUENCE tickets_id_seq RESTART WITH " + ENV.fx.id(100)).update();
        ENV.fx.commitToMain(SampleRepo.withKnownFailingTest(), "sample with a bug");
    }

    private long submit(int issue, String script) {
        github.addIssue(props.repo(), issue, "add() is wrong for negative numbers",
                "add(-3, 2) returns 5.\n\nfake-api-script: " + script + "\n", props.triggerLabel());
        poller.pollOnce();
        return tickets.attemptsFor(props.repo(), issue).getFirst().id();
    }

    @Test
    void theRealCliFixesTheBugAfterTheChecksFailAndThePrHasTheFix() {
        long id = submit(1, "fix-calc");

        workers.newWorker("docker").drain(50);

        Ticket t = tickets.get(id);
        assertThat(t.state()).as(t.failureReason()).isEqualTo(DONE);
        assertThat(tickets.history(id).stream().map(Transition::toState).toList())
                .containsSubsequence(CODING, CHECKS, CODING, CHECKS, PR_OPENED, DONE);
        assertThat(t.retries()).isEqualTo(1);
        assertThat(t.turns()).as("2 turns per agent run").isEqualTo(4);
        assertThat(t.tokensInput()).isEqualTo(4 * 1500);
        assertThat(t.costUsd()).isPositive();
        assertThat(ENV.fx.remoteFile("factory/" + id, "calc.py")).contains("return a + b").contains("# touched");
        assertThat(ENV.fx.remoteFile("main", "calc.py")).contains("abs(a)");
        assertThat(ENV.runner.list()).extracting(s -> s.ticketId()).as("sandbox, proxy and network removed")
                .doesNotContain(id);
    }

    @Test
    void cancellingTheTicketStopsTheAgentWithinTenSeconds() throws Exception {
        long id = submit(2, "slow");
        var worker = CompletableFuture.runAsync(() -> workers.newWorker("docker").drain(10));
        String sbx = "factory-" + id;
        long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (!(ENV.runner.inspect(sbx) != null
                && ENV.runner.exec(sbx, Duration.ofSeconds(10), "ps", "-eo", "args").output().contains("time.sleep(600)"))) {
            assertThat(System.nanoTime()).as("agent started its long step").isLessThan(until);
            Thread.sleep(300);
        }

        long cancelled = System.nanoTime();
        ticketService.cancel(id, "stop it");
        worker.get(30, java.util.concurrent.TimeUnit.SECONDS);

        assertThat(Duration.ofNanos(System.nanoTime() - cancelled)).isLessThan(Duration.ofSeconds(10));
        assertThat(tickets.get(id).state()).isEqualTo(CANCELLED);
        assertThat(ENV.runner.inspect(sbx)).as("sandbox removed, so nothing of the agent is left").isNull();
        assertThat(ENV.runner.inspect(ModelApiProxy.containerName(id))).as("proxy removed").isNull();
    }
}
