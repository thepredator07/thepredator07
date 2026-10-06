package com.ticketfactory.ticket;

import static com.ticketfactory.ticket.TicketState.*;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The fixed ticket lifecycle. This table is the single source of truth: anything not listed is rejected.
 *
 * <pre>
 * RECEIVED -> SANDBOX_READY -> CODING -> CHECKS -> PR_OPENED -> AWAITING_APPROVAL -> DONE
 *                                 ^         |
 *                                 +---------+  (checks failed: back to CODING with feedback)
 * Any non-terminal state -> FAILED | CANCELLED
 * </pre>
 */
public final class TicketStateMachine {

    private static final Map<TicketState, Set<TicketState>> ALLOWED = new EnumMap<>(TicketState.class);

    static {
        ALLOWED.put(RECEIVED, EnumSet.of(SANDBOX_READY, FAILED, CANCELLED));
        ALLOWED.put(SANDBOX_READY, EnumSet.of(CODING, FAILED, CANCELLED));
        ALLOWED.put(CODING, EnumSet.of(CHECKS, FAILED, CANCELLED));
        ALLOWED.put(CHECKS, EnumSet.of(PR_OPENED, CODING, FAILED, CANCELLED));
        ALLOWED.put(PR_OPENED, EnumSet.of(AWAITING_APPROVAL, FAILED, CANCELLED));
        ALLOWED.put(AWAITING_APPROVAL, EnumSet.of(DONE, FAILED, CANCELLED));
        ALLOWED.put(DONE, EnumSet.noneOf(TicketState.class));
        ALLOWED.put(FAILED, EnumSet.noneOf(TicketState.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(TicketState.class));
    }

    private TicketStateMachine() {
    }

    public static boolean canTransition(TicketState from, TicketState to) {
        return ALLOWED.get(from).contains(to);
    }

    /** Throws {@link InvalidTransitionException} unless {@code from -> to} is in the table. */
    public static void validate(TicketState from, TicketState to) {
        if (from == null || to == null || !canTransition(from, to)) {
            throw new InvalidTransitionException(from, to);
        }
    }

    public static Set<TicketState> allowedFrom(TicketState from) {
        return Collections.unmodifiableSet(ALLOWED.get(from));
    }
}
