package com.ticketfactory.queue;

import java.time.Duration;

/** What a {@link JobHandler} wants the queue to do with the job it just ran. */
public sealed interface JobOutcome {

    /** The ticket reached a terminal state; the job is finished. */
    record Complete() implements JobOutcome {
    }

    /** Put the job back in the queue to run again after {@code delay} (waiting, or retrying a step). */
    record Reschedule(Duration delay, String note) implements JobOutcome {
    }

    /** The handler noticed it lost the lease and stopped. Leave the job alone: someone else owns it now. */
    record Abandon(String reason) implements JobOutcome {
    }

    static JobOutcome complete() {
        return new Complete();
    }

    static JobOutcome reschedule(Duration delay, String note) {
        return new Reschedule(delay, note);
    }

    static JobOutcome abandon(String reason) {
        return new Abandon(reason);
    }
}
