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
        Worker w = new Worker("w", queue, (job, ctx) -> JobOutcome.complete(), props.worker());
        assertThat(w.runOnce()).isFalse();
    }

    @Test
    void completeOutcomeFinishesJob() {
        long t = enqueueTicket();
        new Worker("w", queue, (job, ctx) -> JobOutcome.complete(), props.worker()).runOnce();
        assertThat(queue.findByTicket(t)).singleElement().extracting(Job::status).isEqualTo(JobStatus.DONE);
    }

    @Test
    void rescheduleOutcomePutsJobBack() {
        long t = enqueueTicket();
        new Worker("w", queue, (job, ctx) -> JobOutcome.reschedule(Duration.ofHours(1), "later"), props.worker()).runOnce();
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
        Worker w = new Worker("w", queue, (job, ctx) -> {
            calls.incrementAndGet();
            throw new IllegalStateException("kaboom");
        }, cfg);

        assertThat(w.drain(10)).isEqualTo(3);

        assertThat(calls.get()).isEqualTo(3);
        Job job = queue.findByTicket(t).getFirst();
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.lastError()).contains("kaboom");
    }

    private static FactoryProperties.Worker shortLease(Duration lease) {
        return new FactoryProperties.Worker(false, 1, Duration.ofMillis(10), lease, Duration.ZERO, Duration.ZERO, 3);
    }

    @Test
    void heartbeatKeepsALongJobOwnedWhileReapersRun() throws Exception {
        long t = enqueueTicket();
        Duration lease = Duration.ofMillis(150);
        AtomicInteger reclaimed = new AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean stillOwnedAtEnd = new java.util.concurrent.atomic.AtomicBoolean();
        Worker slow = new Worker("slow", queue, (job, ctx) -> {
            long end = System.currentTimeMillis() + 800; // over 5x the lease
            while (System.currentTimeMillis() < end) {
                reclaimed.addAndGet(queue.releaseExpiredLeases(lease)); // an aggressive reaper elsewhere
                assertThat(queue.claim("thief")).as("nobody else can take it").isEmpty();
                sleep(20);
            }
            stillOwnedAtEnd.set(ctx.stillOwned());
            return JobOutcome.complete();
        }, shortLease(lease));

        assertThat(slow.runOnce()).isTrue();

        assertThat(reclaimed.get()).isZero();
        assertThat(stillOwnedAtEnd.get()).isTrue();
        assertThat(queue.findByTicket(t)).singleElement().extracting(Job::status).isEqualTo(JobStatus.DONE);
    }

    @Test
    void handlerSeesLeaseLossAndItsResultIsDiscarded() throws Exception {
        long t = enqueueTicket();
        Duration lease = Duration.ofMillis(90);
        java.util.concurrent.atomic.AtomicBoolean sawLoss = new java.util.concurrent.atomic.AtomicBoolean();
        Worker victim = new Worker("victim", queue, (job, ctx) -> {
            // Simulate another worker taking over (as if this one had been cut off from the DB).
            jdbc.sql("UPDATE jobs SET locked_by = 'other', attempts = attempts + 1 WHERE id = :id")
                    .param("id", job.id()).update();
            long end = System.currentTimeMillis() + 1000;
            while (ctx.stillOwned() && System.currentTimeMillis() < end) {
                sleep(10);
            }
            sawLoss.set(!ctx.stillOwned());
            return JobOutcome.complete(); // must be ignored
        }, shortLease(lease));

        victim.runOnce();

        assertThat(sawLoss.get()).as("heartbeat noticed the lease was gone").isTrue();
        Job job = queue.findByTicket(t).getFirst();
        assertThat(job.status()).as("stale complete was not applied").isEqualTo(JobStatus.RUNNING);
        assertThat(job.lockedBy()).isEqualTo("other");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
