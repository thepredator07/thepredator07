package com.ticketfactory.pipeline;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.fake.FakeAgentRunner;
import com.ticketfactory.fake.FakeChecksRunner;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.fake.FakeSandboxRunner;
import com.ticketfactory.intake.GitHubPoller;
import com.ticketfactory.queue.JobQueue;
import com.ticketfactory.queue.Worker;
import com.ticketfactory.queue.WorkerPool;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.TicketState;
import com.ticketfactory.ticket.Transition;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

abstract class PipelineTestSupport extends AbstractIntegrationTest {

    @Autowired protected FakeGitHubClient github;
    @Autowired protected FakeSandboxRunner sandbox;
    @Autowired protected FakeAgentRunner agent;
    @Autowired protected FakeChecksRunner checks;
    @Autowired protected GitHubPoller poller;
    @Autowired protected WorkerPool workers;
    @Autowired protected TicketRepository tickets;
    @Autowired protected JobQueue queue;
    @Autowired protected FactoryProperties props;

    protected Worker worker;

    @BeforeEach
    void resetFakes() {
        github.reset();
        sandbox.reset();
        agent.reset();
        checks.reset();
        worker = workers.newWorker("test");
    }

    /** Opens an issue on the fake GitHub, polls it in, and returns the new ticket id. */
    protected long submit(int issue, String title, String body) {
        github.addIssue(props.repo(), issue, title, body, props.triggerLabel());
        poller.pollOnce();
        return tickets.findAll(null, 1000).stream()
                .filter(t -> t.issueNumber() == issue).findFirst().orElseThrow().id();
    }

    protected void runUntilIdle() {
        worker.drain(200);
    }

    protected Ticket ticket(long id) {
        return tickets.get(id);
    }

    protected List<TicketState> states(long id) {
        return tickets.history(id).stream().map(Transition::toState).toList();
    }

    /** Makes a parked job (e.g. waiting for approval) runnable now. */
    protected void wakeUpJobs() {
        jdbc.sql("UPDATE jobs SET run_after = now() WHERE status = 'PENDING'").update();
    }
}
