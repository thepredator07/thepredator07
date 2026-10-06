package com.ticketfactory.queue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL-backed job queue. Claiming uses {@code FOR UPDATE SKIP LOCKED} inside a single
 * {@code UPDATE ... RETURNING}, so concurrent workers never receive the same row.
 */
@Component
public class JobQueue {

    private static final RowMapper<Job> JOB = (rs, i) -> new Job(
            rs.getLong("id"),
            rs.getLong("ticket_id"),
            JobStatus.valueOf(rs.getString("status")),
            rs.getInt("attempts"),
            toInstant(rs.getTimestamp("run_after")),
            rs.getString("locked_by"),
            toInstant(rs.getTimestamp("locked_at")),
            rs.getString("last_error"));

    private final JdbcClient jdbc;
    private final Clock clock;

    public JobQueue(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Enqueues work for a ticket. No-op if the ticket already has a pending or running job. */
    public boolean enqueue(long ticketId) {
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.sql("""
                        INSERT INTO jobs (ticket_id, status, run_after, created_at, updated_at)
                        VALUES (:ticket, 'PENDING', :now, :now, :now)
                        ON CONFLICT (ticket_id) WHERE status IN ('PENDING','RUNNING') DO NOTHING""")
                .param("ticket", ticketId).param("now", now).update() == 1;
    }

    /** Atomically claims the oldest runnable job for {@code workerId}, or returns empty. */
    public Optional<Job> claim(String workerId) {
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.sql("""
                        UPDATE jobs SET status = 'RUNNING', locked_by = :worker, locked_at = :now,
                                        attempts = attempts + 1, updated_at = :now
                        WHERE id = (
                            SELECT id FROM jobs
                            WHERE status = 'PENDING' AND run_after <= :now
                            ORDER BY run_after, id
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED)
                        RETURNING *""")
                .param("worker", workerId).param("now", now)
                .query(JOB).optional();
    }

    public void complete(long jobId) {
        finish(jobId, JobStatus.DONE, null);
    }

    public void fail(long jobId, String error) {
        finish(jobId, JobStatus.FAILED, error);
    }

    /** Releases the job back to PENDING, runnable after {@code delay}. */
    public void reschedule(long jobId, Duration delay, String note) {
        Instant now = clock.instant();
        jdbc.sql("""
                        UPDATE jobs SET status = 'PENDING', run_after = :runAfter, locked_by = NULL, locked_at = NULL,
                                        last_error = :note, updated_at = :now
                        WHERE id = :id""")
                .param("id", jobId).param("runAfter", Timestamp.from(now.plus(delay)))
                .param("note", note).param("now", Timestamp.from(now)).update();
    }

    /** Returns RUNNING jobs whose worker died (lease expired) to PENDING. Returns how many were recovered. */
    public int releaseExpiredLeases(Duration lease) {
        Instant now = clock.instant();
        return jdbc.sql("""
                        UPDATE jobs SET status = 'PENDING', locked_by = NULL, locked_at = NULL,
                                        last_error = 'lease expired, recovered', updated_at = :now
                        WHERE status = 'RUNNING' AND locked_at < :cutoff""")
                .param("now", Timestamp.from(now)).param("cutoff", Timestamp.from(now.minus(lease))).update();
    }

    /** Makes a parked job for this ticket runnable now (e.g. after a PR was approved). */
    public void wakeUp(long ticketId) {
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.sql("UPDATE jobs SET run_after = :now, updated_at = :now WHERE ticket_id = :t AND status = 'PENDING'")
                .param("t", ticketId).param("now", now).update();
    }

    public Optional<Job> findActiveForTicket(long ticketId) {
        return jdbc.sql("SELECT * FROM jobs WHERE ticket_id = :t AND status IN ('PENDING','RUNNING')")
                .param("t", ticketId).query(JOB).optional();
    }

    public List<Job> findByTicket(long ticketId) {
        return jdbc.sql("SELECT * FROM jobs WHERE ticket_id = :t ORDER BY id").param("t", ticketId).query(JOB).list();
    }

    public Job get(long jobId) {
        return jdbc.sql("SELECT * FROM jobs WHERE id = :id").param("id", jobId).query(JOB).single();
    }

    public long countByStatus(JobStatus status) {
        return jdbc.sql("SELECT count(*) FROM jobs WHERE status = :s").param("s", status.name())
                .query(Long.class).single();
    }

    private void finish(long jobId, JobStatus status, String error) {
        jdbc.sql("""
                        UPDATE jobs SET status = :status, locked_by = NULL, locked_at = NULL,
                                        last_error = COALESCE(:error, last_error), updated_at = :now
                        WHERE id = :id""")
                .param("id", jobId).param("status", status.name()).param("error", error)
                .param("now", Timestamp.from(clock.instant())).update();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
