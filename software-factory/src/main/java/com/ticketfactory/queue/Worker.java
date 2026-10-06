package com.ticketfactory.queue;

import com.ticketfactory.FactoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Claims one job at a time and hands it to the {@link JobHandler}. Stateless; safe to run many in parallel. */
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
        try {
            switch (handler.handle(job)) {
                case JobOutcome.Complete c -> queue.complete(job.id());
                case JobOutcome.Reschedule r -> queue.reschedule(job.id(), r.delay(), r.note());
            }
        } catch (RuntimeException e) {
            // The handler deals with expected step failures itself; this is a bug or an infrastructure error.
            log.error("Worker {} crashed on job {} (ticket {})", id, job.id(), job.ticketId(), e);
            if (job.attempts() >= config.maxJobAttempts()) {
                queue.fail(job.id(), "gave up after " + job.attempts() + " attempts: " + e);
            } else {
                queue.reschedule(job.id(), config.retryBackoff(), "worker error: " + e);
            }
        }
        return true;
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
