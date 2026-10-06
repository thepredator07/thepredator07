package com.ticketfactory.queue;

/** Does the actual work for a claimed job. Implemented by the ticket pipeline. */
@FunctionalInterface
public interface JobHandler {

    JobOutcome handle(Job job, JobContext context);
}
