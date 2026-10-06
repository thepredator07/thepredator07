package com.ticketfactory.fake;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.integration.ChecksRunner;
import com.ticketfactory.integration.SandboxRunner;
import com.ticketfactory.integration.StepFailedException;
import com.ticketfactory.integration.TicketContext;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FakeBehaviorTest {

    private static TicketContext ticket(long id, String body) {
        return new TicketContext(id, "acme/app", (int) id, "Title", body, "factory/" + id);
    }

    @Test
    void modesDecideFailurePerCall() {
        assertThat(FakeBehavior.SUCCEED.shouldFail(1)).isFalse();
        assertThat(FakeBehavior.FAIL.shouldFail(1)).isTrue();
        assertThat(FakeBehavior.FAIL.shouldFail(99)).isTrue();
        FakeBehavior f2 = FakeBehavior.failThenSucceed(2);
        assertThat(f2.shouldFail(1)).isTrue();
        assertThat(f2.shouldFail(2)).isTrue();
        assertThat(f2.shouldFail(3)).isFalse();
    }

    @Test
    void parsesTextForms() {
        assertThat(FakeBehavior.parse("succeed")).isEqualTo(FakeBehavior.SUCCEED);
        assertThat(FakeBehavior.parse("FAIL")).isEqualTo(FakeBehavior.FAIL);
        assertThat(FakeBehavior.parse("fail-then-succeed")).isEqualTo(FakeBehavior.failThenSucceed(1));
        assertThat(FakeBehavior.parse("fail_then_succeed 3")).isEqualTo(FakeBehavior.failThenSucceed(3));
    }

    @Test
    void scriptReadsDirectivesFromIssueBody() {
        FakeScript s = FakeScript.parse("""
                Login is broken.

                fake-agent: fail-then-succeed 2
                Fake-Agent-Cost: 1.25
                fake-agent-delay: PT1S
                fake-approval: Pending""");
        assertThat(s.behavior("agent", FakeBehavior.SUCCEED)).isEqualTo(FakeBehavior.failThenSucceed(2));
        assertThat(s.behavior("checks", FakeBehavior.FAIL)).isEqualTo(FakeBehavior.FAIL);
        assertThat(s.decimal("agent-cost")).contains(new BigDecimal("1.25"));
        assertThat(s.duration("agent-delay")).contains(Duration.ofSeconds(1));
        assertThat(s.text("approval")).contains("pending");
        assertThat(FakeScript.parse(null).integer("agent-turns")).isEmpty();
    }

    @Test
    void failThenSucceedCountsPerTicket() {
        FakeSandboxRunner sandbox = new FakeSandboxRunner(FakeBehavior.failThenSucceed(1));
        TicketContext a = ticket(1, "");
        TicketContext b = ticket(2, "");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sandbox.prepare(a))
                .isInstanceOf(StepFailedException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sandbox.prepare(b))
                .isInstanceOf(StepFailedException.class);
        SandboxRunner.Sandbox sbx = sandbox.prepare(a);
        assertThat(sbx.id()).isEqualTo("fake-sbx-1");
        assertThat(sandbox.isLive(sbx.id())).isTrue();
        sandbox.destroy(sbx.id());
        assertThat(sandbox.isLive(sbx.id())).isFalse();
        assertThat(sandbox.callCount(1)).isEqualTo(2);
    }

    @Test
    void scriptOverridesConfiguredDefaultAndRuntimeOverrideBeatsConfig() {
        FakeChecksRunner checks = new FakeChecksRunner(FakeBehavior.SUCCEED);
        assertThat(checks.run(ticket(1, "fake-checks: fail"), "s").passed()).isFalse();
        assertThat(checks.run(ticket(2, ""), "s").passed()).isTrue();

        checks.setDefaultBehavior(FakeBehavior.FAIL);
        ChecksRunner.ChecksResult r = checks.run(ticket(3, ""), "s");
        assertThat(r.passed()).isFalse();
        assertThat(r.output()).contains("Failures: 1");
        checks.reset();
        assertThat(checks.run(ticket(3, ""), "s").passed()).isTrue();
    }

    @Test
    void agentReportsUsageEvenWhenItFails() {
        FakeAgentRunner agent = new FakeAgentRunner(new FakeProperties.Agent("fail", 1, 5, 1000, 200,
                new BigDecimal("0.30"), Duration.ZERO));
        var result = agent.run(new com.ticketfactory.integration.AgentRunner.AgentRequest(
                ticket(1, ""), "s", "prompt", null, 40, new BigDecimal("2")));
        assertThat(result.success()).isFalse();
        assertThat(result.turns()).isEqualTo(5);
        assertThat(result.inputTokens()).isEqualTo(5000);
        assertThat(result.outputTokens()).isEqualTo(1000);
        assertThat(result.costUsd()).isEqualByComparingTo("0.30");
    }

    @Test
    void agentUsageCanBeScriptedPerTicket() {
        FakeAgentRunner agent = new FakeAgentRunner(new FakeProperties.Agent("succeed", 1, 5, 1000, 200,
                new BigDecimal("0.30"), Duration.ZERO));
        var result = agent.run(new com.ticketfactory.integration.AgentRunner.AgentRequest(
                ticket(1, "fake-agent-turns: 50\nfake-agent-cost: 9.99"), "s", "p", null, 40, BigDecimal.TEN));
        assertThat(result.success()).isTrue();
        assertThat(result.turns()).isEqualTo(50);
        assertThat(result.costUsd()).isEqualByComparingTo("9.99");
    }
}
