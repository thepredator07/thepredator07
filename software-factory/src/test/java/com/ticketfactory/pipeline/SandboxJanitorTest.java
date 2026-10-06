package com.ticketfactory.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.fake.FakeSandboxRunner;
import com.ticketfactory.integration.TicketContext;
import com.ticketfactory.ticket.TicketService;
import com.ticketfactory.ticket.TicketState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SandboxJanitorTest extends AbstractIntegrationTest {

    @Autowired SandboxJanitor janitor;
    @Autowired FakeSandboxRunner sandbox;
    @Autowired TicketService tickets;

    @BeforeEach
    void reset() {
        sandbox.reset();
    }

    private String sandboxFor(long ticketId) {
        return sandbox.prepare(new TicketContext(ticketId, "acme/app", 1, "t", "", "factory/" + ticketId)).id();
    }

    @Test
    void removesSandboxesOfFinishedOrMissingTicketsAndKeepsRunningOnes() {
        long running = tickets.receive("acme/app", 1, "running", "").orElseThrow();
        long finished = tickets.receive("acme/app", 2, "finished", "").orElseThrow();
        tickets.transition(finished, TicketState.RECEIVED, TicketState.FAILED, "crashed before cleanup");
        String keep = sandboxFor(running);
        String orphanOfFinished = sandboxFor(finished);
        String orphanOfMissing = sandboxFor(999_999);

        assertThat(janitor.sweep()).isEqualTo(2);

        assertThat(sandbox.isLive(keep)).isTrue();
        assertThat(sandbox.isLive(orphanOfFinished)).isFalse();
        assertThat(sandbox.isLive(orphanOfMissing)).isFalse();
        assertThat(janitor.sweep()).as("nothing left to do").isZero();
    }
}
