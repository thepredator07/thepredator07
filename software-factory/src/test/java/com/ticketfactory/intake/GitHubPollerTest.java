package com.ticketfactory.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.queue.JobQueue;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.TicketState;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GitHubPollerTest extends AbstractIntegrationTest {

    @Autowired
    GitHubPoller poller;

    @Autowired
    FakeGitHubClient github;

    @Autowired
    TicketRepository tickets;

    @Autowired
    JobQueue queue;

    @Autowired
    FactoryProperties props;

    @BeforeEach
    void resetFake() {
        github.reset();
    }

    @Test
    void createsTicketsOnlyForLabeledIssuesInTheConfiguredRepo() {
        String repo = props.repo();
        github.addIssue(repo, 1, "Add dark mode", "please", props.triggerLabel(), "ui");
        github.addIssue(repo, 2, "Unrelated bug", "", "bug");
        github.addIssue("other/repo", 3, "Wrong repo", "", props.triggerLabel());

        assertThat(poller.pollOnce()).isEqualTo(1);

        List<Ticket> all = tickets.findAll(null, 100);
        assertThat(all).singleElement().satisfies(t -> {
            assertThat(t.issueNumber()).isEqualTo(1);
            assertThat(t.title()).isEqualTo("Add dark mode");
            assertThat(t.state()).isEqualTo(TicketState.RECEIVED);
            assertThat(queue.findActiveForTicket(t.id())).isPresent();
        });
    }

    @Test
    void pollingAgainDoesNotDuplicateTickets() {
        github.addIssue(props.repo(), 1, "Add dark mode", "", props.triggerLabel());
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(poller.pollOnce()).isZero();
        github.addIssue(props.repo(), 2, "Second", "", props.triggerLabel());
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(tickets.count()).isEqualTo(2);
    }
}
