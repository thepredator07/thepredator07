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
        queue.complete(queue.claim("w1").orElseThrow().id());
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

        queue.reschedule(job.id(), Duration.ofHours(1), "waiting");
        assertThat(queue.claim("w1")).isEmpty();
        assertThat(queue.get(job.id()).lastError()).isEqualTo("waiting");

        queue.reschedule(job.id(), Duration.ZERO, "go");
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
        queue.fail(job.id(), "boom");
        Job failed = queue.get(job.id());
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.lastError()).isEqualTo("boom");
        assertThat(failed.lockedBy()).isNull();
    }
}
