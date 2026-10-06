package com.ticketfactory.ticket;

import java.time.Instant;

public record Transition(long id, long ticketId, TicketState fromState, TicketState toState, String reason,
                         Instant createdAt) {
}
