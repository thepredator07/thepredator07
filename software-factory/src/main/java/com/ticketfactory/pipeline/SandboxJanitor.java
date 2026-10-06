package com.ticketfactory.pipeline;

import com.ticketfactory.integration.SandboxRunner;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes sandboxes nobody will clean up: the ticket finished (or no longer exists) but its sandbox is still there,
 * typically because the app was killed between the last step and the cleanup. Sandboxes of running tickets are kept:
 * lease recovery hands those tickets to another worker, which reuses the sandbox.
 */
@Component
public class SandboxJanitor {

    private static final Logger log = LoggerFactory.getLogger(SandboxJanitor.class);

    private final SandboxRunner sandboxes;
    private final TicketRepository tickets;

    private final boolean enabled;

    public SandboxJanitor(SandboxRunner sandboxes, TicketRepository tickets,
                          @Value("${factory.sandbox.janitor-enabled:true}") boolean enabled) {
        this.sandboxes = sandboxes;
        this.tickets = tickets;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${factory.sandbox.janitor-interval:PT10M}", initialDelayString = "PT1M")
    public void scheduledSweep() {
        if (!enabled) {
            return;
        }
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("Sandbox janitor failed: {}", e.toString());
        }
    }

    /** Returns how many sandboxes were removed. */
    public int sweep() {
        int removed = 0;
        for (SandboxRunner.Sandbox s : sandboxes.list()) {
            Optional<Ticket> ticket = tickets.findById(s.ticketId());
            if (ticket.isEmpty() || ticket.get().state().isTerminal()) {
                log.info("Janitor: removing orphaned sandbox {} (ticket {} {})", s.id(), s.ticketId(),
                        ticket.map(t -> t.state().name()).orElse("missing"));
                sandboxes.destroy(s.id());
                removed++;
            }
        }
        return removed;
    }
}
