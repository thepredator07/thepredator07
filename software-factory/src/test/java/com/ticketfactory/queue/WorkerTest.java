package com.ticketfactory.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.ticket.TicketService;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WorkerTest extends AbstractIntegrationTest {

    @Autowired
    JobQueue queue;

    @Autowired
    TicketService tickets;

    @Autowired
    FactoryProperties props;

    private long enqueueTicket() {
        long t = tickets.receive("acme/app", 7, "x", "").orElseThrow();
        queue.enqueue(t);
        return t;
    }

    @Test
    void returnsFalseWhenNothingToDo() {
        Worker w = new Worker("w", queue, job -> JobOutcome.complete(), props.worker());
        assertThat(w.runOnce()).isFalse();
    }

    @Test
    void completeOutcomeFinishesJob() {
        long t = enqueueTicket();
        new Worker("w", queue, job -> JobOutcome.complete(), props.worker()).runOnce();
        assertThat(queue.findByTicket(t)).singleElement().extracting(Job::status).isEqualTo(JobStatus.DONE);
    }

    @Test
    void rescheduleOutcomePutsJobBack() {
        long t = enqueueTicket();
        new Worker("w", queue, job -> JobOutcome.reschedule(Duration.ofHours(1), "later"), props.worker()).runOnce();
        Job job = queue.findActiveForTicket(t).orElseThrow();
        assertThat(job.status()).isEqualTo(JobStatus.PENDING);
        assertThat(job.lastError()).isEqualTo("later");
    }

    @Test
    void crashingHandlerIsRetriedThenFailed() {
        long t = enqueueTicket();
        AtomicInteger calls = new AtomicInteger();
        FactoryProperties.Worker cfg = new FactoryProperties.Worker(false, 1, Duration.ofMillis(10),
                Duration.ofMinutes(1), Duration.ZERO, Duration.ZERO, 3);
        Worker w = new Worker("w", queue, job -> {
            calls.incrementAndGet();
            throw new IllegalStateException("kaboom");
        }, cfg);

        assertThat(w.drain(10)).isEqualTo(3);

        assertThat(calls.get()).isEqualTo(3);
        Job job = queue.findByTicket(t).getFirst();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.lastError()).contains("kaboom");
    }
}
