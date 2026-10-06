package com.ticketfactory.fake;

import com.ticketfactory.integration.AgentRunner;
import java.math.BigDecimal;
import java.time.Duration;

/** Pretends to edit code. Reports turns, tokens and cost so guardrails can be exercised. */
public class FakeAgentRunner extends AbstractFake implements AgentRunner {

    private final FakeProperties.Agent config;

    public FakeAgentRunner(FakeProperties.Agent config) {
        super("agent", config.behavior());
        this.config = config;
    }

    @Override
    public AgentResult run(AgentRequest request) {
        FakeScript script = FakeScript.parse(request.ticket().body());
        sleep(script.duration("agent-delay").orElse(config.delay()));

        // A well-behaved agent stays within the turns it was given. A scripted value deliberately ignores the limit,
        // to simulate an agent that overshoots (that is what the pipeline's turn guardrail is for).
        int turns = script.integer("agent-turns").orElse(Math.min(config.turnsPerRun(), request.maxTurns()));
        BigDecimal cost = script.decimal("agent-cost").orElse(config.costPerRunUsd());
        long in = turns * config.inputTokensPerTurn();
        long out = turns * config.outputTokensPerTurn();

        if (nextCallFails(request.ticket())) {
            return new AgentResult(false, "fake agent: could not produce a working change (simulated)",
                    turns, in, out, cost);
        }
        String summary = request.feedback() == null
                ? "fake agent: implemented '" + request.ticket().title() + "'"
                : "fake agent: fixed failing checks";
        return new AgentResult(true, summary, turns, in, out, cost);
    }

    private static void sleep(Duration d) {
        if (d.isZero() || d.isNegative()) {
            return;
        }
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
