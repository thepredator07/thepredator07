package com.ticketfactory.ticket;

import static com.ticketfactory.ticket.TicketState.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketfactory.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TicketServiceTest extends AbstractIntegrationTest {

    @Autowired
    TicketService service;

    @Autowired
    TicketRepository tickets;

    private long newTicket() {
        return service.receive("acme/app", 42, "Fix login", "body").orElseThrow();
    }

    @Test
    void receiveCreatesTicketAndRecordsInitialTransition() {
        long id = newTicket();

        Ticket t = tickets.get(id);
        assertThat(t.state()).isEqualTo(RECEIVED);
        List<Transition> history = tickets.history(id);
        assertThat(history).singleElement().satisfies(tr -> {
            assertThat(tr.fromState()).isNull();
            assertThat(tr.toState()).isEqualTo(RECEIVED);
            assertThat(tr.createdAt()).isNotNull();
        });
    }

    @Test
    void receiveIsIdempotentPerIssue() {
        newTicket();
        assertThat(service.receive("acme/app", 42, "Fix login", "body")).isEmpty();
        assertThat(tickets.count()).isEqualTo(1);
    }

    @Test
    void everyTransitionIsPersistedInOrderWithTimestamps() {
        long id = newTicket();
        service.transition(id, RECEIVED, SANDBOX_READY, "sandbox up");
        service.transition(id, SANDBOX_READY, CODING, "agent started");
        service.transition(id, CODING, CHECKS, "agent done");

        List<Transition> history = tickets.history(id);
        assertThat(history).extracting(Transition::toState)
                .containsExactly(RECEIVED, SANDBOX_READY, CODING, CHECKS);
        assertThat(history).extracting(Transition::fromState)
                .containsExactly(null, RECEIVED, SANDBOX_READY, CODING);
        assertThat(history).allSatisfy(tr -> assertThat(tr.createdAt()).isNotNull());
        for (int i = 1; i < history.size(); i++) {
            assertThat(history.get(i).createdAt()).isAfterOrEqualTo(history.get(i - 1).createdAt());
        }
        assertThat(history.get(1).reason()).isEqualTo("sandbox up");
        assertThat(tickets.get(id).state()).isEqualTo(CHECKS);
    }

    @Test
    void invalidTransitionIsRejectedAndNothingIsWritten() {
        long id = newTicket();

        assertThatThrownBy(() -> service.transition(id, RECEIVED, DONE, "skip ahead"))
                .isInstanceOf(InvalidTransitionException.class);

        assertThat(tickets.get(id).state()).isEqualTo(RECEIVED);
        assertThat(tickets.history(id)).hasSize(1);
    }

    @Test
    void staleExpectedStateIsRejected() {
        long id = newTicket();
        service.transition(id, RECEIVED, SANDBOX_READY, "first");

        assertThatThrownBy(() -> service.transition(id, RECEIVED, SANDBOX_READY, "second"))
                .isInstanceOf(TicketService.ConcurrentTransitionException.class);
        assertThat(tickets.history(id)).hasSize(2);
    }

    @Test
    void terminalTransitionRecordsFinishTimeDurationAndReason() {
        long id = newTicket();
        service.transition(id, RECEIVED, FAILED, "sandbox exploded");

        Ticket t = tickets.get(id);
        assertThat(t.state()).isEqualTo(FAILED);
        assertThat(t.finishedAt()).isNotNull();
        assertThat(t.durationMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(t.failureReason()).isEqualTo("sandbox exploded");
    }

    @Test
    void cancelWorksFromAnyActiveStateButNotTerminal() {
        long id = newTicket();
        service.transition(id, RECEIVED, SANDBOX_READY, "up");

        assertThat(service.cancel(id, "user cancelled")).isTrue();
        assertThat(tickets.get(id).state()).isEqualTo(CANCELLED);
        assertThat(service.cancel(id, "again")).isFalse();
        assertThat(tickets.history(id)).extracting(Transition::toState).last().isEqualTo(CANCELLED);
    }
}
