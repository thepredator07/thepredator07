package com.ticketfactory.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.ticket.TicketService;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JobQueueTest extends AbstractIntegrationTest {

    @Autowired
    JobQueue queue;

    @Autowired
    TicketService tickets;

    private long ticket(int issue) {
        return tickets.receive("acme/app", issue, "Issue " + issue, "").orElseThrow();
    }

    @Test
    void claimReturnsEmptyWhenQueueIsEmpty() {
        assertThat(queue.claim("w1")).isEmpty();
    }

    @Test
    void claimMarksJobRunningAndCountsAttempt() {
        long t = ticket(1);
        queue.enqueue(t);

        Job job = queue.claim("w1").orElseThrow();

        assertThat(job.ticketId()).isEqualTo(t);
        assertThat(job.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(job.lockedBy()).isEqualTo("w1");
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(queue.claim("w2")).as("already claimed").isEmpty();
    }

    @Test
    void onlyOneActiveJobPerTicket() {
        long t = ticket(1);
        assertThat(queue.enqueue(t)).isTrue();
        assertThat(queue.enqueue(t)).isFalse();
        queue.claim("w1");
        assertThat(queue.enqueue(t)).as("running job still blocks a second one").isFalse();
    }

    @Test
    void completedTicketCanBeEnqueuedAgain() {
        long t = ticket(1);
        queue.enqueue(t);
        assertThat(queue.complete(queue.claim("w1").orElseThrow())).isTrue();
        assertThat(queue.enqueue(t)).isTrue();
    }

    @Test
    void claimsInFifoOrder() {
        long a = ticket(1);
        long b = ticket(2);
        queue.enqueue(a);
        queue.enqueue(b);
        assertThat(queue.claim("w").orElseThrow().ticketId()).isEqualTo(a);
        assertThat(queue.claim("w").orElseThrow().ticketId()).isEqualTo(b);
    }

    @Test
    void rescheduledJobIsNotClaimableUntilRunAfter() {
        long t = ticket(1);
        queue.enqueue(t);
        Job job = queue.claim("w1").orElseThrow();

        assertThat(queue.reschedule(job, Duration.ofHours(1), "waiting")).isTrue();
        assertThat(queue.claim("w1")).isEmpty();
        assertThat(queue.get(job.id()).lastError()).isEqualTo("waiting");

        queue.wakeUp(t);
        assertThat(queue.claim("w2")).get().extracting(Job::attempts).isEqualTo(2);
    }

    @Test
    void expiredLeaseIsRecovered() throws Exception {
        long t = ticket(1);
        queue.enqueue(t);
        queue.claim("dead-worker").orElseThrow();

        assertThat(queue.releaseExpiredLeases(Duration.ofHours(1))).as("lease still valid").isZero();
        Thread.sleep(20);
        assertThat(queue.releaseExpiredLeases(Duration.ofMillis(1))).isEqualTo(1);
        assertThat(queue.claim("w2")).get().extracting(Job::lockedBy).isEqualTo("w2");
    }

    @Test
    void failRecordsError() {
        long t = ticket(1);
        queue.enqueue(t);
        Job job = queue.claim("w1").orElseThrow();
        assertThat(queue.fail(job, "boom")).isTrue();
        Job failed = queue.get(job.id());
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.lastError()).isEqualTo("boom");
        assertThat(failed.lockedBy()).isNull();
    }

    // ---- M0: leases are fenced and kept alive by heartbeats ----

    @Test
    void workerThatLostItsLeaseCannotFinishTheJob() throws Exception {
        long t = ticket(1);
        queue.enqueue(t);
        Job a = queue.claim("worker-A").orElseThrow();
        Thread.sleep(20);
        queue.releaseExpiredLeases(Duration.ofMillis(1));
        Job b = queue.claim("worker-B").orElseThrow();
        assertThat(b.id()).isEqualTo(a.id());

        assertThat(queue.complete(a)).as("stale complete").isFalse();
        assertThat(queue.fail(a, "x")).as("stale fail").isFalse();
        assertThat(queue.reschedule(a, Duration.ZERO, "x")).as("stale reschedule").isFalse();
        assertThat(queue.heartbeat(a)).as("stale heartbeat").isFalse();

        Job current = queue.get(b.id());
        assertThat(current.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(current.lockedBy()).isEqualTo("worker-B");
        assertThat(queue.complete(b)).isTrue();
        assertThat(queue.get(b.id()).status()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void sameWorkerIdOnALaterAttemptIsStillFenced() throws Exception {
        long t = ticket(1);
        queue.enqueue(t);
        Job first = queue.claim("w").orElseThrow();
        Thread.sleep(20);
        queue.releaseExpiredLeases(Duration.ofMillis(1));
        Job second = queue.claim("w").orElseThrow();
        assertThat(second.attempts()).isEqualTo(first.attempts() + 1);
        assertThat(queue.complete(first)).isFalse();
        assertThat(queue.complete(second)).isTrue();
    }

    @Test
    void heartbeatKeepsTheLeaseFromExpiring() throws Exception {
        long t = ticket(1);
        queue.enqueue(t);
        Job job = queue.claim("w").orElseThrow();
        for (int i = 0; i < 5; i++) {
            Thread.sleep(40);
            assertThat(queue.heartbeat(job)).isTrue();
            assertThat(queue.releaseExpiredLeases(Duration.ofMillis(100))).as("renewed, not expired").isZero();
        }
        Thread.sleep(150);
        assertThat(queue.releaseExpiredLeases(Duration.ofMillis(100))).as("no heartbeat, expired").isEqualTo(1);
    }
}
