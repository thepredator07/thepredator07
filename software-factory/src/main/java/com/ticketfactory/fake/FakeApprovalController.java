package com.ticketfactory.fake;

import com.ticketfactory.integration.GitHubClient.PrStatus;
import com.ticketfactory.queue.JobQueue;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.TicketState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Fake mode only: stands in for a human approving the PR on GitHub. In real mode approval comes from GitHub. */
@Controller
@ConditionalOnProperty(name = "factory.integrations", havingValue = "fake", matchIfMissing = true)
public class FakeApprovalController {

    private final TicketRepository tickets;
    private final JobQueue queue;
    private final FakeGitHubClient github;

    public FakeApprovalController(TicketRepository tickets, JobQueue queue, FakeGitHubClient github) {
        this.tickets = tickets;
        this.queue = queue;
        this.github = github;
    }

    @PostMapping("/tickets/{id}/approve")
    public String approve(@PathVariable long id, RedirectAttributes redirect) {
        Ticket t = tickets.get(id);
        if (t.state() != TicketState.AWAITING_APPROVAL || t.prNumber() == null) {
            redirect.addFlashAttribute("message", "Only tickets awaiting approval can be approved.");
        } else {
            github.setPullRequestStatus(t.prNumber(), PrStatus.APPROVED);
            queue.wakeUp(id);
            redirect.addFlashAttribute("message", "PR #" + t.prNumber() + " approved on the fake GitHub.");
        }
        return "redirect:/tickets/" + id;
    }
}
