package com.ticketfactory.ticket;

public class InvalidTransitionException extends RuntimeException {

    private final TicketState from;
    private final TicketState to;

    public InvalidTransitionException(TicketState from, TicketState to) {
        super("Invalid ticket transition " + from + " -> " + to);
        this.from = from;
        this.to = to;
    }

    public TicketState from() {
        return from;
    }

    public TicketState to() {
        return to;
    }
}
