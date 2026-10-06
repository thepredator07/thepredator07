package com.ticketfactory.eval;

import static com.ticketfactory.ticket.TicketState.FAILED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.integration.AgentRunner;
import com.ticketfactory.integration.ChecksRunner;
import com.ticketfactory.integration.SandboxRunner;
import com.ticketfactory.integration.docker.DockerSandboxFixture;
import com.ticketfactory.intake.GitHubPoller;
import com.ticketfactory.queue.WorkerPool;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import java.io.IOException;
import java.math.RoundingMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/** Live: a deliberately oversized ticket with a 3-turn limit must fail on the guardrail, not run on. */
@Tag("agent-eval")
@Import(AgentGuardrailEvalTest.Config.class)
@TestPropertySource(properties = {
        "factory.repo=" + DockerSandboxFixture.REPO,
        "factory.guardrails.max-turns=3",
        "factory.guardrails.max-cost-usd=1.00",
        "factory.guardrails.hard-timeout=PT10M",
        "factory.guardrails.max-retries=2"})
class AgentGuardrailEvalTest extends AbstractIntegrationTest {

    static EvalEnvironment env;

    @BeforeAll
    static void start() throws IOException {
        assumeTrue(EvalEnvironment.credentialAvailable(), "no ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN");
        env = new EvalEnvironment();
    }

    @AfterAll
    static void stop() throws IOException {
        if (env != null) {
            env.close();
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        SandboxRunner evalSandbox() {
            return env.runner;
        }

        @Bean
        @Primary
        AgentRunner evalAgent() {
            return env.agent();
        }

        @Bean
        @Primary
        ChecksRunner evalChecks() {
            return env.checks();
        }
    }

    @Autowired FakeGitHubClient github;
    @Autowired GitHubPoller poller;
    @Autowired WorkerPool workers;
    @Autowired TicketRepository tickets;
    @Autowired FactoryProperties props;

    @Test
    void anOversizedTicketFailsOnTheTurnGuardrail() {
        jdbc.sql("ALTER SEQUENCE tickets_id_seq RESTART WITH " + env.fx.id(500)).update();
        env.fx.replaceMain(EvalFixtures.all().getFirst().files(), "oversized fixture");
        github.addIssue(props.repo(), 1, "Rewrite the project as a full web application",
                "Turn this into a complete web application with user accounts, a database layer, a REST API, an "
                        + "admin UI and full test coverage for every part.", props.triggerLabel());
        poller.pollOnce();
        long id = tickets.attemptsFor(props.repo(), 1).getFirst().id();

        workers.newWorker("eval").drain(50);

        Ticket t = tickets.get(id);
        EvalReport.append("""
                ## Guardrail: oversized ticket with a 3-turn limit

                Final state %s after %d turns, $%s. Reason: %s
                """.formatted(t.state(), t.turns(), t.costUsd().setScale(2, RoundingMode.HALF_UP),
                t.failureReason()));
        assertThat(t.state()).isEqualTo(FAILED);
        assertThat(t.failureReason()).containsAnyOf("turn", "no budget left");
        assertThat(t.turns()).as("the CLI's --max-turns kept it near the limit").isLessThanOrEqualTo(4);
    }
}
