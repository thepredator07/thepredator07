package com.ticketfactory.queue;

import java.time.Instant;

public record Job(long id, long ticketId, JobStatus status, int attempts, Instant runAfter, String lockedBy,
                  Instant lockedAt, String lastError) {
}
