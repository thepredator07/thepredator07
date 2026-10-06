package com.ticketfactory.queue;

import com.ticketfactory.FactoryProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Claims one job at a time and hands it to the {@link JobHandler}. Stateless between jobs; safe to run many in
 * parallel, in one process or across several.
 *
 * <p>While the handler runs, a heartbeat thread renews the lease every {@code lease-timeout / 3}. If a renewal
 * finds the lease gone, the handler sees {@link JobContext#stillOwned()} turn false and must stop.
 */
public class Worker {

    private static final Logger log = LoggerFactory.getLogger(Worker.class);

    private final String id;
    private final JobQueue queue;
    private final JobHandler handler;
    private final FactoryProperties.Worker config;

    public Worker(String id, JobQueue queue, JobHandler handler, FactoryProperties.Worker config) {
        this.id = id;
        this.queue = queue;
        this.handler = handler;
        this.config = config;
    }

    public String id() {
        return id;
    }

    /** Claims and runs a single job. Returns false if there was nothing to do. */
    public boolean runOnce() {
        Job job = queue.claim(id).orElse(null);
        if (job == null) {
            return false;
        }
        AtomicBoolean owned = new AtomicBoolean(true);
        Thread heartbeat = Thread.ofVirtual().name("heartbeat-" + id + "-job-" + job.id())
                .start(() -> heartbeatLoop(job, owned));
        try {
            JobOutcome outcome = handler.handle(job, owned::get);
            boolean applied = switch (outcome) {
                case JobOutcome.Complete c -> queue.complete(job);
                case JobOutcome.Reschedule r -> queue.reschedule(job, r.delay(), r.note());
                case JobOutcome.Abandon a -> {
                    log.warn("Worker {} abandoned job {} (ticket {}): {}", id, job.id(), job.ticketId(), a.reason());
                    yield true;
                }
            };
            if (!applied) {
                log.warn("Worker {} lost the lease on job {} (ticket {}) before finishing; result discarded",
                        id, job.id(), job.ticketId());
            }
        } catch (RuntimeException e) {
            // The handler deals with expected step failures itself; this is a bug or an infrastructure error.
            log.error("Worker {} crashed on job {} (ticket {})", id, job.id(), job.ticketId(), e);
            if (job.attempts() >= config.maxJobAttempts()) {
                queue.fail(job, "gave up after " + job.attempts() + " attempts: " + e);
            } else {
                queue.reschedule(job, config.retryBackoff(), "worker error: " + e);
            }
        } finally {
            heartbeat.interrupt();
        }
        return true;
    }

    private void heartbeatLoop(Job job, AtomicBoolean owned) {
        Duration interval = heartbeatInterval();
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                return; // handler finished
            }
            try {
                if (!queue.heartbeat(job)) {
                    owned.set(false);
                    log.warn("Worker {} could not renew lease on job {} (ticket {})", id, job.id(), job.ticketId());
                    return;
                }
            } catch (RuntimeException e) {
                // DB briefly unreachable: keep trying. If it stays down past the lease, the reaper hands the job
                // to another worker and the next successful renewal reports the lease as lost.
                log.warn("Heartbeat for job {} failed: {}", job.id(), e.toString());
            }
        }
    }

    private Duration heartbeatInterval() {
        Duration third = config.leaseTimeout().dividedBy(3);
        return third.isZero() ? Duration.ofMillis(1) : third;
    }

    /** Processes jobs until the queue has nothing runnable. Used by tests and the demo. */
    public int drain(int maxJobs) {
        int processed = 0;
        while (processed < maxJobs && runOnce()) {
            processed++;
        }
        return processed;
    }
}
