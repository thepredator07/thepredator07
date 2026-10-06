package com.ticketfactory.pipeline;

import static com.ticketfactory.ticket.TicketState.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.integration.AgentRunner;
import com.ticketfactory.integration.ChecksRunner;
import com.ticketfactory.integration.SandboxRunner;
import com.ticketfactory.integration.checks.ChecksProperties;
import com.ticketfactory.integration.checks.SampleRepo;
import com.ticketfactory.integration.checks.SandboxChecksRunner;
import com.ticketfactory.integration.docker.DockerSandboxFixture;
import com.ticketfactory.integration.docker.HostGit;
import com.ticketfactory.intake.GitHubPoller;
import com.ticketfactory.queue.WorkerPool;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.Transition;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * The checks loop for real: a Docker sandbox, the real checks runner and a sample repo with a known failing test.
 * Only the agent is scripted (M5 brings the real one): it fixes the bug once the checks output shows it.
 */
@Import(DockerChecksPipelineTest.Config.class)
@TestPropertySource(properties = "factory.repo=" + DockerSandboxFixture.REPO)
class DockerChecksPipelineTest extends AbstractIntegrationTest {

    static final DockerSandboxFixture FX;
    static final ScriptedAgent AGENT = new ScriptedAgent();

    static {
        try {
            FX = new DockerSandboxFixture();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Stands in for the coding agent. First run: a harmless change that leaves the bug. With checks feedback naming
     * the failing test: the real fix. It commits like the agent will, because only commits are published.
     */
    static final class ScriptedAgent implements AgentRunner {
        final List<String> feedbackSeen = new CopyOnWriteArrayList<>();

        @Override
        public AgentResult run(AgentRequest r) {
            feedbackSeen.add(String.valueOf(r.feedback()));
            String change = r.feedback() != null && r.feedback().contains("FAIL: test_add_negative")
                    ? "sed -i 's/abs(a) + b/a + b/' calc.py && git commit -qam 'Fix add for negative numbers'"
                    : "echo '# calculator' >> calc.py && git add -A && git commit -qm 'Add comment'";
            var out = FX.runner.exec(r.sandboxId(), Duration.ofSeconds(30), "sh", "-c",
                    "cd /workspace/repo && " + change);
            return new AgentResult(out.ok(), out.ok() ? "changed calc.py" : out.output(), 3, 1000, 200,
                    new BigDecimal("0.05"));
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        SandboxRunner dockerSandbox() {
            return FX.runner;
        }

        @Bean
        @Primary
        AgentRunner scriptedAgent() {
            return AGENT;
        }

        @Bean
        @Primary
        ChecksRunner sandboxChecks(FactoryProperties props) {
            HostGit git = new HostGit(FX.root.resolve("checks-host"),
                    "file://" + FX.root.resolve("remotes") + "/{repo}.git", null, Duration.ofMinutes(2));
            return new SandboxChecksRunner(FX.runner, git,
                    new ChecksProperties(".factory.yml", "", Duration.ofMinutes(5), Duration.ofMinutes(30)),
                    props.baseBranch());
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
    @Autowired FactoryProperties props;

    @BeforeEach
    void reset() {
        github.reset();
        AGENT.feedbackSeen.clear();
        jdbc.sql("ALTER SEQUENCE tickets_id_seq RESTART WITH " + FX.id(100)).update();
    }

    private long submit(int issue) {
        github.addIssue(props.repo(), issue, "add() is wrong for negative numbers", "", props.triggerLabel());
        poller.pollOnce();
        return tickets.attemptsFor(props.repo(), issue).getFirst().id();
    }

    @Test
    void failingChecksGoBackToTheAgentWhichFixesTheBugAndThePrHasTheFix() {
        FX.commitToMain(SampleRepo.withKnownFailingTest(), "sample with a bug");
        long id = submit(1);

        workers.newWorker("docker").drain(50);

        Ticket t = tickets.get(id);
        assertThat(t.state()).as(t.failureReason()).isEqualTo(DONE);
        assertThat(t.retries()).isEqualTo(1);
        assertThat(tickets.history(id).stream().map(Transition::toState).toList())
                .containsSubsequence(CODING, CHECKS, CODING, CHECKS, PR_OPENED, DONE);
        assertThat(AGENT.feedbackSeen).hasSize(2);
        assertThat(AGENT.feedbackSeen.get(0)).isEqualTo("null");
        assertThat(AGENT.feedbackSeen.get(1)).contains("FAIL: test_add_negative").contains("AssertionError: -1 != 5");
        assertThat(FX.remoteFile("factory/" + id, "calc.py")).contains("return a + b").contains("# calculator");
        assertThat(FX.remoteFile("main", "calc.py")).as("main untouched").contains("abs(a)");
    }

    @Test
    void aRepoWithoutACheckCommandFailsTheTicketAtOnceWithoutRetries() {
        FX.commitToMain(java.util.Map.of(".factory.yml", "# nothing configured yet\n"), "empty config");
        long id = submit(2);

        workers.newWorker("docker").drain(50);

        Ticket t = tickets.get(id);
        assertThat(t.state()).isEqualTo(FAILED);
        assertThat(t.failureReason()).startsWith("CHECKS failed: .factory.yml has no 'checks' section");
        assertThat(t.retries()).isZero();
        assertThat(AGENT.feedbackSeen).hasSize(1);
        assertThat(FX.remoteHead("factory/" + id)).as("nothing pushed").isNull();
        assertThat(FX.runner.inspect("factory-" + id)).as("sandbox cleaned up").isNull();
    }
}
