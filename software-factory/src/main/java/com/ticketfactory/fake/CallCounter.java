package com.ticketfactory.fake;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts calls per ticket, so fail-then-succeed is deterministic per ticket regardless of other tickets. */
final class CallCounter {

    private final Map<Long, AtomicInteger> calls = new ConcurrentHashMap<>();

    int next(long ticketId) {
        return calls.computeIfAbsent(ticketId, k -> new AtomicInteger()).incrementAndGet();
    }

    int count(long ticketId) {
        AtomicInteger c = calls.get(ticketId);
        return c == null ? 0 : c.get();
    }

    int total() {
        return calls.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    void reset() {
        calls.clear();
    }
}
