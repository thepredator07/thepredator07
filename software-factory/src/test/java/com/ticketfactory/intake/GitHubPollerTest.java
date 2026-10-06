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
        assertThat(tickets.findAll(null, 10)).extracting(Ticket::id)
                .as("re-polling must not burn ids").containsExactlyInAnyOrder(1L, 2L);
    }

    // ---- M1: reconciliation ----

    @Autowired com.ticketfactory.queue.WorkerPool workers;

    private Ticket only(int issue) {
        return tickets.attemptsFor(props.repo(), issue).getFirst();
    }

    @Test
    void closingTheIssueCancelsAnAttemptThatHasNoPrYet() {
        github.addIssue(props.repo(), 1, "Never mind", "", props.triggerLabel());
        poller.pollOnce();
        github.closeIssue(props.repo(), 1);

        assertThat(poller.poll().cancelled()).isEqualTo(1);

        Ticket t = only(1);
        assertThat(t.state()).isEqualTo(TicketState.CANCELLED);
        assertThat(tickets.history(t.id()).getLast().reason()).contains("closed or lost the 'factory' label");
        workers.newWorker("p").drain(10);
        assertThat(queue.findActiveForTicket(t.id())).as("its job finishes without doing work").isEmpty();
    }

    @Test
    void removingTheLabelCancelsTheAttempt() {
        github.addIssue(props.repo(), 2, "Oops, wrong label", "", props.triggerLabel());
        poller.pollOnce();
        github.removeLabel(props.repo(), 2, props.triggerLabel());

        poller.pollOnce();

        assertThat(only(2).state()).isEqualTo(TicketState.CANCELLED);
    }

    @Test
    void closingTheIssueAfterThePrIsOpenDoesNotCancel() {
        // Merging a PR with "Closes #N" closes the issue; that must not cancel the ticket about to be DONE.
        github.addIssue(props.repo(), 3, "Needs review", "fake-approval: pending", props.triggerLabel());
        poller.pollOnce();
        workers.newWorker("p").drain(20);
        Ticket waiting = only(3);
        assertThat(waiting.state()).isEqualTo(TicketState.AWAITING_APPROVAL);

        github.closeIssue(props.repo(), 3);
        assertThat(poller.poll().cancelled()).isZero();
        assertThat(only(3).state()).isEqualTo(TicketState.AWAITING_APPROVAL);

        github.setPullRequestStatus(waiting.prNumber(), com.ticketfactory.integration.GitHubClient.PrStatus.APPROVED);
        queue.wakeUp(waiting.id());
        workers.newWorker("p").drain(20);
        assertThat(only(3).state()).isEqualTo(TicketState.DONE);
    }

    @Test
    void aFailedListingCancelsNothing() {
        github.addIssue(props.repo(), 4, "Keep me", "", props.triggerLabel());
        poller.pollOnce();
        github.setListingFails(true);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> poller.poll())
                .isInstanceOf(com.ticketfactory.integration.StepFailedException.class);
        poller.scheduledPoll(); // the scheduled path logs and carries on

        assertThat(only(4).state()).isEqualTo(TicketState.RECEIVED);
    }
}
