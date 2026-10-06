package com.ticketfactory.integration;

import java.math.BigDecimal;

/**
 * Runs the coding agent inside a sandbox. Phase 1: {@code FakeAgentRunner}.
 * TODO(phase-2): run Claude Code headless in the sandbox, passing maxTurns, and parse its usage report.
 */
public interface AgentRunner {

    AgentResult run(AgentRequest request);

    /**
     * @param feedback      output of the previous failed checks run, or null on the first attempt
     * @param maxTurns      turns left in this ticket's budget
     * @param maxCostUsd    dollars left in this ticket's budget
     */
    record AgentRequest(TicketContext ticket, String sandboxId, String prompt, String feedback, int maxTurns,
                        BigDecimal maxCostUsd) {
    }

    /** Usage is reported even when the agent fails, because the tokens were still spent. */
    record AgentResult(boolean success, String summary, int turns, long inputTokens, long outputTokens,
                       BigDecimal costUsd) {
    }
}
