package com.ticketfactory.integration;

import java.time.Duration;

/**
 * An external API refused the call because of a rate limit. Not the ticket's fault: the pipeline waits
 * {@link #retryAfter()} and tries again without counting it against the ticket's retries.
 */
public class RateLimitedException extends StepFailedException {

    private final Duration retryAfter;

    public RateLimitedException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
