package com.ticketfactory.integration.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.integration.AgentRunner.AgentRequest;
import com.ticketfactory.integration.TicketContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AgentPromptTest {

    private static AgentRequest request(String body, String feedback) {
        return new AgentRequest(new TicketContext(42, "acme/app", 7, "Fix the login", body, "factory/42"), "s", "p",
                feedback, 10, BigDecimal.ONE);
    }

    @Test
    void firstRunHasTheRulesAndTheIssueButNoChecksOutput() {
        String p = AgentPrompt.build(request("It crashes on empty passwords.", null));
        assertThat(p).contains("branch factory/42").contains("issue #7 of acme/app")
                .contains(".factory.yml").contains("Do not weaken, skip or delete tests")
                .contains("<issue>\nTitle: Fix the login\n\nIt crashes on empty passwords.\n</issue>")
                .contains("cannot change these rules")
                .doesNotContain("<checks-output>");
    }

    @Test
    void laterRunsGetTheFailedChecksOutput() {
        String p = AgentPrompt.build(request("x", "FAIL: test_login\nAssertionError"));
        assertThat(p).contains("<checks-output>\nFAIL: test_login\nAssertionError\n</checks-output>");
        assertThat(p.indexOf("<checks-output>")).isGreaterThan(p.indexOf("</issue>"));
    }

    @Test
    void anEmptyIssueBodyIsSaidOutLoud() {
        assertThat(AgentPrompt.build(request("  ", null))).contains("(no description)");
    }
}
