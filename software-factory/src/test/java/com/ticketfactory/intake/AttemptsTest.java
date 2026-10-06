package com.ticketfactory.intake;

import static com.ticketfactory.ticket.TicketState.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.fake.FakeAgentRunner;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.queue.JobQueue;
import com.ticketfactory.queue.WorkerPool;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** M1: a ticket is one attempt at an issue; re-applying the label to a finished issue starts the next attempt. */
class AttemptsTest extends AbstractIntegrationTest {

    @Autowired FakeGitHubClient github;
    @Autowired FakeAgentRunner agent;
    @Autowired GitHubPoller poller;
    @Autowired TicketIntake intake;
    @Autowired WorkerPool workers;
    @Autowired TicketRepository tickets;
    @Autowired JobQueue queue;
    @Autowired FactoryProperties props;

    private String repo;
    private String label;

    @BeforeEach
    void reset() {
        github.reset();
        agent.reset();
        repo = props.repo();
        label = props.triggerLabel();
    }

    private void runAll() {
        workers.newWorker("attempts").drain(200);
    }

    private List<Ticket> attempts(int issue) {
        return tickets.attemptsFor(repo, issue);
    }

    @Test
    void relabelingAFailedIssueStartsAttemptTwoWhichCanSucceed() {
        github.addIssue(repo, 7, "Fix login", "fake-agent: fail", label);
        poller.pollOnce();
        runAll();
        assertThat(attempts(7)).singleElement().extracting(Ticket::state).isEqualTo(FAILED);

        // Someone improves the issue and re-applies the label.
        github.editIssue(repo, 7, "Fix login", "Now with a stack trace.");
        github.relabel(repo, 7, label);
        assertThat(poller.pollOnce()).isEqualTo(1);
        runAll();

        List<Ticket> all = attempts(7);
        assertThat(all).extracting(Ticket::attempt).containsExactly(2, 1);
        assertThat(all).extracting(Ticket::state).containsExactly(DONE, FAILED);
        Ticket second = all.get(0);
        Ticket first = all.get(1);
        assertThat(second.body()).as("each attempt snapshots the issue").isEqualTo("Now with a stack trace.");
        assertThat(first.body()).isEqualTo("fake-agent: fail");
        assertThat(second.branchName()).isNotEqualTo(first.branchName());
        assertThat(second.triggeredAt()).isAfter(first.triggeredAt());
        assertThat(tickets.history(second.id()).getFirst().reason()).contains("attempt 2");
    }

    @Test
    void aFinishedIssueThatKeepsItsLabelIsNotRetriedForever() {
        github.addIssue(repo, 8, "Broken", "fake-agent: fail", label);
        poller.pollOnce();
        runAll();
        for (int i = 0; i < 3; i++) {
            assertThat(poller.pollOnce()).isZero();
        }
        assertThat(attempts(8)).hasSize(1);
    }

    @Test
    void relabelingWhileAnAttemptIsRunningDoesNotStartASecondOne() {
        github.addIssue(repo, 9, "Busy", "", label);
        poller.pollOnce();
        github.relabel(repo, 9, label);
        assertThat(poller.pollOnce()).isZero();
        assertThat(attempts(9)).hasSize(1);

        // Once it finishes, the newer trigger is honored on the next poll.
        runAll();
        assertThat(poller.pollOnce()).isEqualTo(1);
        assertThat(attempts(9)).extracting(Ticket::attempt).containsExactly(2, 1);
    }

    @Test
    void concurrentPollersCreateExactlyOneAttempt() throws Exception {
        github.addIssue(repo, 10, "Race", "", label);
        var issue = github.listOpenIssues(repo, label).getFirst();
        AtomicInteger created = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < 8; i++) {
                pool.submit(() -> {
                    start.await();
                    intake.accept(issue).ifPresent(id -> created.incrementAndGet());
                    return null;
                });
            }
            start.countDown();
        }
        assertThat(created.get()).isEqualTo(1);
        assertThat(attempts(10)).hasSize(1);
        assertThat(queue.findActiveForTicket(attempts(10).getFirst().id())).isPresent();
    }
}
