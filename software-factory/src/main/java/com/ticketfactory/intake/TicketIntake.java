package com.ticketfactory.intake;

import com.ticketfactory.queue.JobQueue;
import com.ticketfactory.ticket.TicketService;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creates a ticket and its first job in one transaction, so a ticket is never left without work. */
@Service
public class TicketIntake {

    private final TicketService tickets;
    private final JobQueue queue;

    public TicketIntake(TicketService tickets, JobQueue queue) {
        this.tickets = tickets;
        this.queue = queue;
    }

    @Transactional
    public Optional<Long> accept(String repo, int issueNumber, String title, String body) {
        Optional<Long> id = tickets.receive(repo, issueNumber, title, body);
        id.ifPresent(queue::enqueue);
        return id;
    }
}
