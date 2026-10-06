package com.ticketfactory.ticket;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The only way to change a ticket's state. Validates against the state machine and writes the audit row. */
@Service
public class TicketService {

    private final TicketRepository tickets;
    private final Clock clock;

    public TicketService(TicketRepository tickets, Clock clock) {
        this.tickets = tickets;
        this.clock = clock;
    }

    /** Creates a ticket for an issue (state RECEIVED) unless one exists. Returns the new ticket id. */
    @Transactional
    public Optional<Long> receive(String repo, int issueNumber, String title, String body) {
        Instant now = clock.instant();
        Optional<Long> id = tickets.insertIfAbsent(repo, issueNumber, title, body, now);
        id.ifPresent(ticketId -> tickets.insertTransition(ticketId, null, TicketState.RECEIVED,
                "Picked up issue #" + issueNumber, now));
        return id;
    }

    /**
     * Moves a ticket from {@code expected} to {@code to}. Rejects transitions the state machine does not allow,
     * and fails if another actor changed the state in the meantime (optimistic check on the current state).
     */
    @Transactional
    public Ticket transition(long ticketId, TicketState expected, TicketState to, String reason) {
        TicketStateMachine.validate(expected, to);
        Instant now = clock.instant();
        if (tickets.updateState(ticketId, expected, to, now) != 1) {
            TicketState actual = tickets.get(ticketId).state();
            throw new ConcurrentTransitionException(ticketId, expected, actual, to);
        }
        tickets.insertTransition(ticketId, expected, to, reason, now);
        if (to.isTerminal()) {
            tickets.markFinished(ticketId, now, to == TicketState.FAILED ? reason : null);
        }
        return tickets.get(ticketId);
    }

    /** Cancels a ticket from whatever non-terminal state it is in. Returns false if it was already finished. */
    @Transactional
    public boolean cancel(long ticketId, String reason) {
        Ticket ticket = tickets.get(ticketId);
        if (ticket.state().isTerminal()) {
            return false;
        }
        transition(ticketId, ticket.state(), TicketState.CANCELLED, reason);
        return true;
    }

    public static class ConcurrentTransitionException extends RuntimeException {
        public ConcurrentTransitionException(long id, TicketState expected, TicketState actual, TicketState to) {
            super("Ticket " + id + " expected in " + expected + " but was " + actual + " (wanted " + to + ")");
        }
    }
}
