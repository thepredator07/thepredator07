package com.ticketfactory.intake;

import com.ticketfactory.integration.GitHubClient;
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

    /** Starts a new attempt for the issue if it has none running and this trigger is new. */
    @Transactional
    public Optional<Long> accept(GitHubClient.Issue issue) {
        Optional<Long> id = tickets.receive(issue.repo(), issue.number(), issue.title(), issue.body(),
                issue.triggeredAt());
        id.ifPresent(queue::enqueue);
        return id;
    }

    /** For tests and the demo: an issue triggered right now. */
    @Transactional
    public Optional<Long> accept(String repo, int issueNumber, String title, String body) {
        Optional<Long> id = tickets.receive(repo, issueNumber, title, body);
        id.ifPresent(queue::enqueue);
        return id;
    }
}
