package com.ticketfactory.ticket;

/** Every state a ticket can be in. The allowed moves between them live in {@link TicketStateMachine}. */
public enum TicketState {
    RECEIVED,
    SANDBOX_READY,
    CODING,
    CHECKS,
    PR_OPENED,
    AWAITING_APPROVAL,
    DONE,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == CANCELLED;
    }
}
