package com.ticketfactory.metrics;

import com.ticketfactory.queue.JobStatus;
import com.ticketfactory.ticket.TicketState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Gauges read from the database, so every instance reports the same, whole-factory numbers:
 * {@code factory_jobs{status}} (queue depth) and {@code factory_tickets{state}} (tickets per state). One query each
 * per scrape, at most every 5 seconds.
 */
@Component
public class QueueGauges implements MeterBinder {

    private static final long CACHE_NANOS = 5_000_000_000L;

    private final JdbcClient jdbc;
    private volatile Snapshot snapshot = new Snapshot(0, new EnumMap<>(JobStatus.class), new EnumMap<>(TicketState.class));

    private record Snapshot(long takenAt, Map<JobStatus, Long> jobs, Map<TicketState, Long> tickets) {
    }

    public QueueGauges(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (JobStatus s : JobStatus.values()) {
            Gauge.builder("factory.jobs", () -> current().jobs().getOrDefault(s, 0L))
                    .description("Jobs in the queue by status").tag("status", s.name()).register(registry);
        }
        for (TicketState s : TicketState.values()) {
            Gauge.builder("factory.tickets", () -> current().tickets().getOrDefault(s, 0L))
                    .description("Tickets by state").tag("state", s.name()).register(registry);
        }
    }

    private Snapshot current() {
        Snapshot s = snapshot;
        if (System.nanoTime() - s.takenAt() < CACHE_NANOS && s.takenAt() != 0) {
            return s;
        }
        synchronized (this) {
            if (System.nanoTime() - snapshot.takenAt() < CACHE_NANOS && snapshot.takenAt() != 0) {
                return snapshot;
            }
            Map<JobStatus, Long> jobs = new EnumMap<>(JobStatus.class);
            jdbc.sql("SELECT status, count(*) AS n FROM jobs GROUP BY status")
                    .query((rs, i) -> jobs.put(JobStatus.valueOf(rs.getString("status")), rs.getLong("n"))).list();
            Map<TicketState, Long> tickets = new EnumMap<>(TicketState.class);
            jdbc.sql("SELECT state, count(*) AS n FROM tickets GROUP BY state")
                    .query((rs, i) -> tickets.put(TicketState.valueOf(rs.getString("state")), rs.getLong("n"))).list();
            snapshot = new Snapshot(System.nanoTime(), jobs, tickets);
            return snapshot;
        }
    }
}
