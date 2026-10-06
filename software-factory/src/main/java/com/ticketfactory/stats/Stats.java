package com.ticketfactory.stats;

import com.ticketfactory.ticket.TicketState;
import java.math.BigDecimal;
import java.util.Map;

/**
 * Factory-wide numbers.
 *
 * @param successRate        DONE / (DONE + FAILED), 0..1, or null if nothing finished yet. CANCELLED is excluded
 *                           because a human chose to stop it, so it says nothing about the factory's ability.
 * @param avgDurationMs      mean duration of DONE and FAILED tickets
 * @param avgCostUsd         mean cost of finished (DONE, FAILED, CANCELLED) tickets
 * @param totalCostUsd       cost of all tickets, including the ones still running
 */
public record Stats(
        long total,
        long done,
        long failed,
        long cancelled,
        long active,
        Double successRate,
        Long avgDurationMs,
        BigDecimal avgCostUsd,
        BigDecimal totalCostUsd,
        long totalTokens,
        long totalRetries,
        BigDecimal avgCostDone,
        BigDecimal avgCostFailed,
        Map<TicketState, Long> byState) {

    public long finished() {
        return done + failed + cancelled;
    }
}
