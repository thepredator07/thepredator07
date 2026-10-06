package com.ticketfactory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ticketfactory.integration.AgentRunner;
import com.ticketfactory.integration.AgentRunner.AgentRequest;
import com.ticketfactory.integration.AgentRunner.AgentResult;
import com.ticketfactory.integration.TicketContext;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * What every {@link AgentRunner} must do. The Claude Code runner (M5) subclasses this. Note what is *not* promised:
 * staying under the cost budget. Usage is only known after a run, so the pipeline's cost guardrail enforces that.
 */
public abstract class AgentRunnerContract {

    protected abstract AgentRunner runner();

    /** A ticket the implementation can work on quickly (fake: plain; real: a trivial fixture issue). */
    protected abstract TicketContext easyTicket();

    /** A ticket whose run takes at least several seconds (used to test interruption). */
    protected abstract TicketContext slowTicket();

    /** A runner that fails to produce a change, if the implementation can simulate it. */
    protected Optional<AgentRunner> failingRunner() {
        return Optional.empty();
    }

    protected String sandboxId() {
        return "contract-sandbox";
    }

    private AgentRequest request(TicketContext t, int maxTurns, String feedback) {
        return new AgentRequest(t, sandboxId(), "Resolve: " + t.title(), feedback, maxTurns, new BigDecimal("2.00"));
    }

    @Test
    void reportsUsage() {
        AgentResult r = runner().run(request(easyTicket(), 40, null));
        assertThat(r.turns()).isPositive();
        assertThat(r.inputTokens()).isPositive();
        assertThat(r.outputTokens()).isPositive();
        assertThat(r.costUsd()).isNotNull().isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(r.summary()).isNotBlank();
    }

    @Test
    void staysWithinTheTurnLimitItIsGiven() {
        AgentResult r = runner().run(request(easyTicket(), 3, null));
        assertThat(r.turns()).isBetween(1, 3);
    }

    @Test
    void acceptsFeedbackFromFailedChecks() {
        AgentResult r = runner().run(request(easyTicket(), 40, "[ERROR] LoginServiceTest failed: expected 401"));
        assertThat(r.summary()).isNotBlank();
    }

    @Test
    void failedRunStillReportsUsage() {
        Optional<AgentRunner> failing = failingRunner();
        assumeTrue(failing.isPresent(), "implementation cannot simulate a failed run");
        AgentResult r = failing.get().run(request(easyTicket(), 40, null));
        assertThat(r.success()).isFalse();
        assertThat(r.turns()).isPositive();
        assertThat(r.costUsd()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void interruptionStopsTheRunPromptly() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread t = Thread.ofVirtual().start(() -> {
            try {
                runner().run(request(slowTicket(), 40, null));
            } catch (Throwable e) {
                failure.set(e); // throwing on interrupt is fine
            }
        });
        Thread.sleep(200);
        long start = System.nanoTime();
        t.interrupt();
        t.join(5_000);
        long tookMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(t.isAlive()).as("run still going 5s after interrupt").isFalse();
        assertThat(tookMs).isLessThan(2_000);
    }
}
