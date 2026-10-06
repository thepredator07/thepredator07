package com.ticketfactory.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketState;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "factory.guardrails.hard-timeout=PT1S")
class HardTimeoutTest extends PipelineTestSupport {

    @Test
    void slowAgentIsCutOffAtTheHardTimeout() {
        long id = submit(1, "Slow agent", "fake-agent-delay: PT10S");

        long start = System.nanoTime();
        runUntilIdle();
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        Ticket t = ticket(id);
        assertThat(t.state()).isEqualTo(TicketState.FAILED);
        assertThat(t.failureReason()).contains("guardrail: hard timeout PT1S");
        assertThat(tookMs).as("did not wait for the 10s agent").isLessThan(5_000);
    }
}
