package com.ticketfactory.integration.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticketfactory.integration.AgentRunner.AgentRequest;
import com.ticketfactory.integration.AgentRunner.AgentResult;
import com.ticketfactory.integration.StepFailedException;
import com.ticketfactory.integration.TicketContext;
import com.ticketfactory.integration.docker.DockerSandboxFixture;
import com.ticketfactory.integration.docker.ModelApiProxy;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link ClaudeCodeAgentRunner} with the real Claude Code CLI in the real sandbox, talking through the real proxy to a
 * scripted fake model API. The issue body picks the script ({@code fake-api-script: <name>}).
 */
class ClaudeCodeAgentRunnerTest {

    private AgentTestEnvironment env;
    private ClaudeCodeAgentRunner agent;
    private int next = 1;

    @BeforeEach
    void start() throws Exception {
        env = new AgentTestEnvironment(ModelApiProxy.Mode.OAUTH_TOKEN);
        agent = env.agent();
    }

    @AfterEach
    void stop() throws Exception {
        env.close();
    }

    private TicketContext ticket(String script) {
        long id = env.fx.id(next++);
        return new TicketContext(id, DockerSandboxFixture.REPO, (int) id, "Scripted ticket",
                "Please help.\nfake-api-script: " + script + "\n", "factory/" + id);
    }

    private AgentRequest request(TicketContext t, String sandboxId, String feedback, int maxTurns) {
        return new AgentRequest(t, sandboxId, "unused: the runner builds its own prompt", feedback, maxTurns,
                new BigDecimal("2.00"));
    }

    private String inSandbox(String sbx, String script) {
        return env.runner.exec(sbx, Duration.ofSeconds(20), "sh", "-c", script).output();
    }

    @Test
    void theRealCliChangesTheRepoThroughTheProxyAndItsUsageIsReported() {
        TicketContext t = ticket("edit");
        String sbx = env.runner.prepare(t).id();

        AgentResult r = agent.run(request(t, sbx, null, 10));

        assertThat(r.success()).as(r.summary()).isTrue();
        assertThat(r.summary()).isEqualTo("Scripted run 'edit' finished after 1 step(s).");
        assertThat(r.turns()).isEqualTo(2);
        assertThat(r.inputTokens()).isEqualTo(2 * 1500);
        assertThat(r.outputTokens()).isEqualTo(2 * 50);
        assertThat(r.costUsd()).isPositive();
        assertThat(inSandbox(sbx, "cd /workspace/repo && git log -1 --format=%s && git show --stat HEAD | grep hello"))
                .contains("factory: Scripted ticket (#").contains("hello.txt");
        assertThat(env.proxy.isRunning(t.ticketId())).as("proxy stopped after the run").isFalse();
    }

    @Test
    void theModelSeesTheIssueAndTheChecksOutputButNeverTheRealCredentialReachesTheSandbox() {
        TicketContext t = ticket("edit");
        String sbx = env.runner.prepare(t).id();

        agent.run(request(t, sbx, "FAIL: test_something (test_x.T)\nAssertionError: 1 != 2", 10));

        JsonNode first = env.requests().stream().filter(q -> q.get("body").asText().contains("\"tools\""))
                .findFirst().orElseThrow();
        String body = first.get("body").asText();
        assertThat(body).contains("Resolve GitHub issue #").contains("fake-api-script: edit")
                .contains("<checks-output>").contains("AssertionError: 1 != 2");
        assertThat(first.get("headers").toString()).contains("Bearer " + AgentTestEnvironment.REAL_OAUTH)
                .doesNotContain(ModelApiProxy.PLACEHOLDER_OAUTH_TOKEN);
        List<String> offered = new ArrayList<>();
        try {
            new ObjectMapper().readTree(body).get("tools").forEach(tool -> offered.add(tool.get("name").asText()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(offered).as("no web search, web fetch, remote triggers or scheduling")
                .containsExactlyInAnyOrder("Bash", "Read", "Edit", "Write");
    }

    @Test
    void codeTheAgentRunsSeesOnlyThePlaceholderCredential() {
        TicketContext t = ticket("spy");
        String sbx = env.runner.prepare(t).id();

        agent.run(request(t, sbx, null, 10));

        String seen = inSandbox(sbx, "cat /workspace/repo/spy.txt");
        assertThat(seen).contains("CLAUDE_CODE_OAUTH_TOKEN=" + ModelApiProxy.PLACEHOLDER_OAUTH_TOKEN)
                .contains("ANTHROPIC_BASE_URL=http://model-proxy:8080")
                .doesNotContain(AgentTestEnvironment.REAL_OAUTH).doesNotContain("REAL-");
    }

    @Test
    void theTurnLimitStopsTheAgentAndCountsAsFailure() {
        TicketContext t = ticket("loop");
        String sbx = env.runner.prepare(t).id();

        AgentResult r = agent.run(request(t, sbx, null, 3));

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).startsWith("agent reached the turn limit");
        assertThat(r.turns()).isBetween(3, 4);
        assertThat(r.costUsd()).isPositive();
    }

    @Test
    void anAgentThatChangesNothingHasFailed() {
        TicketContext t = ticket("nothing");
        String sbx = env.runner.prepare(t).id();

        AgentResult r = agent.run(request(t, sbx, null, 10));

        assertThat(r.success()).isFalse();
        assertThat(r.summary()).startsWith("agent finished without changing anything");
        assertThat(r.turns()).isEqualTo(1);
    }

    @Test
    void leavingTheTicketBranchIsAnError() {
        TicketContext t = ticket("switch-branch");
        String sbx = env.runner.prepare(t).id();

        assertThatThrownBy(() -> agent.run(request(t, sbx, null, 10)))
                .isInstanceOf(StepFailedException.class).hasMessageContaining("left the ticket branch");
    }

    @Test
    void cancellingKillsTheAgentAndEverythingItStartedWithinTenSeconds() throws Exception {
        TicketContext t = ticket("slow");
        String sbx = env.runner.prepare(t).id();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<AgentResult> run = executor.submit(() -> agent.run(request(t, sbx, null, 10)));
        long waitUntil = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!inSandbox(sbx, "ps -eo args").contains("time.sleep(600)")) {
            assertThat(System.nanoTime()).as("agent reached its long tool call; ps:\n" + inSandbox(sbx, "ps -eo pid,args") + "\nrun: " + describe(run)).isLessThan(waitUntil);
            Thread.sleep(200);
        }

        long cancelled = System.nanoTime();
        run.cancel(true); // what the pipeline does on cancel, timeout or lease loss
        String ps;
        do {
            Thread.sleep(250);
            ps = inSandbox(sbx, "ps -eo args");
        } while ((ps.contains("claude") || ps.contains("time.sleep"))
                && System.nanoTime() - cancelled < Duration.ofSeconds(15).toNanos());

        assertThat(Duration.ofNanos(System.nanoTime() - cancelled)).isLessThan(Duration.ofSeconds(10));
        assertThat(ps).doesNotContain("claude").doesNotContain("time.sleep(600)").doesNotContain("time.sleep(700)")
                .doesNotContain("defunct");
        long proxyDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (env.proxy.isRunning(t.ticketId()) && System.nanoTime() < proxyDeadline) {
            Thread.sleep(100);
        }
        assertThat(env.proxy.isRunning(t.ticketId())).as("no way out after a cancel").isFalse();
        executor.shutdownNow();
    }

    private static String describe(Future<AgentResult> run) {
        if (!run.isDone()) {
            return "still running";
        }
        try {
            return String.valueOf(run.get());
        } catch (Exception e) {
            return e.toString();
        }
    }

    @Test
    void theCliGetsTheTicketsRemainingBudget() {
        TicketContext t = ticket("edit");
        var cmd = agent.command(new AgentRequest(t, "x", "p", null, 7, new BigDecimal("1.239")));
        assertThat(String.join(" ", cmd)).contains("--max-turns 7").contains("--max-budget-usd 1.23")
                .contains("--dangerously-skip-permissions").contains("--output-format stream-json")
                .doesNotContain("--model");
        assertThat(agent.environment()).contains("ANTHROPIC_BASE_URL=http://model-proxy:8080",
                "CLAUDE_CODE_OAUTH_TOKEN=" + ModelApiProxy.PLACEHOLDER_OAUTH_TOKEN)
                .noneMatch(e -> e.contains("REAL"));
    }
}
