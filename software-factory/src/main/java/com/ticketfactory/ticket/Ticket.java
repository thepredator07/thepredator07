package com.ticketfactory.ticket;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

public record Ticket(
        long id,
        String repo,
        int issueNumber,
        String title,
        String body,
        TicketState state,
        String branchName,
        String sandboxId,
        Integer prNumber,
        String prUrl,
        String lastFeedback,
        String failureReason,
        long tokensInput,
        long tokensOutput,
        BigDecimal costUsd,
        int turns,
        int retries,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        Long durationMs) {

    public long totalTokens() {
        return tokensInput + tokensOutput;
    }

    /** Final duration for finished tickets, time so far for running ones. */
    public Duration elapsed(Instant now) {
        if (durationMs != null) {
            return Duration.ofMillis(durationMs);
        }
        Instant start = startedAt != null ? startedAt : createdAt;
        return Duration.between(start, finishedAt != null ? finishedAt : now);
    }
}
