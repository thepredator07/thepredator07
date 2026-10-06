package com.ticketfactory.queue;

/** What a {@link JobHandler} can ask about the job it is running. */
public interface JobContext {

    /**
     * False once the worker's lease on the job is gone (heartbeat could not renew it, usually because the worker was
     * cut off from the database for longer than the lease). The handler must stop and not touch the ticket again:
     * another worker may already own it.
     */
    boolean stillOwned();

    /** For tests and simple handlers: a context whose lease never goes away. */
    JobContext ALWAYS_OWNED = () -> true;
}
