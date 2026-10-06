package com.ticketfactory.queue;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL-backed job queue.
 *
 * <ul>
 *   <li>Claiming uses {@code FOR UPDATE SKIP LOCKED} inside a single {@code UPDATE ... RETURNING}, so concurrent
 *       workers never receive the same row.</li>
 *   <li>A claim is a lease identified by {@code (id, locked_by, attempts)}. Every later write about the job
 *       (heartbeat, complete, fail, reschedule) is fenced on that triple, so a worker that lost its lease can no
 *       longer change the job.</li>
 *   <li>All scheduling decisions use the database clock ({@code now()}), so app instances with skewed clocks agree
 *       on when a job is due and when a lease has expired.</li>
 * </ul>
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

    /** Matches only the row still leased to the worker that claimed it, for that same attempt. */
    private static final String OWNED = "id = :id AND status = 'RUNNING' AND locked_by = :worker AND attempts = :attempt";

    private final JdbcClient jdbc;

    public JobQueue(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Enqueues work for a ticket. No-op if the ticket already has a pending or running job. */
    public boolean enqueue(long ticketId) {
        return jdbc.sql("""
                        INSERT INTO jobs (ticket_id, status, run_after, created_at, updated_at)
                        VALUES (:ticket, 'PENDING', now(), now(), now())
                        ON CONFLICT (ticket_id) WHERE status IN ('PENDING','RUNNING') DO NOTHING""")
                .param("ticket", ticketId).update() == 1;
    }

    /** Atomically claims the oldest runnable job for {@code workerId}, or returns empty. */
    public Optional<Job> claim(String workerId) {
        return jdbc.sql("""
                        UPDATE jobs SET status = 'RUNNING', locked_by = :worker, locked_at = now(),
                                        attempts = attempts + 1, updated_at = now()
                        WHERE id = (
                            SELECT id FROM jobs
                            WHERE status = 'PENDING' AND run_after <= now()
                            ORDER BY run_after, id
                            LIMIT 1
                            FOR UPDATE SKIP LOCKED)
                        RETURNING *""")
                .param("worker", workerId)
                .query(JOB).optional();
    }

    /** Extends the lease. Returns false if the worker no longer owns the job (it was reclaimed or finished). */
    public boolean heartbeat(Job lease) {
        return owned("UPDATE jobs SET locked_at = now(), updated_at = now() WHERE " + OWNED, lease) == 1;
    }

    /** Marks the job done. Returns false (and changes nothing) if the worker lost the lease. */
    public boolean complete(Job lease) {
        return finish(lease, JobStatus.DONE, null);
    }

    /** Marks the job failed. Returns false (and changes nothing) if the worker lost the lease. */
    public boolean fail(Job lease, String error) {
        return finish(lease, JobStatus.FAILED, error);
    }

    /** Releases the job back to PENDING, runnable after {@code delay}. Returns false if the lease was lost. */
    public boolean reschedule(Job lease, Duration delay, String note) {
        return jdbc.sql("""
                        UPDATE jobs SET status = 'PENDING',
                                        run_after = now() + (:delayMs * interval '1 millisecond'),
                                        locked_by = NULL, locked_at = NULL, last_error = :note, updated_at = now()
                        WHERE\s""" + OWNED)
                .param("delayMs", delay.toMillis()).param("note", note)
                .param("id", lease.id()).param("worker", lease.lockedBy()).param("attempt", lease.attempts())
                .update() == 1;
    }

    /**
     * Returns RUNNING jobs whose worker stopped heartbeating for longer than {@code lease} to PENDING.
     * Returns how many were recovered.
     */
    public int releaseExpiredLeases(Duration lease) {
        return jdbc.sql("""
                        UPDATE jobs SET status = 'PENDING', locked_by = NULL, locked_at = NULL,
                                        last_error = 'lease expired, recovered', updated_at = now()
                        WHERE status = 'RUNNING' AND locked_at < now() - (:leaseMs * interval '1 millisecond')""")
                .param("leaseMs", lease.toMillis()).update();
    }

    /** Makes a parked job for this ticket runnable now (e.g. after a PR was approved). */
    public void wakeUp(long ticketId) {
        jdbc.sql("UPDATE jobs SET run_after = now(), updated_at = now() WHERE ticket_id = :t AND status = 'PENDING'")
                .param("t", ticketId).update();
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

    private boolean finish(Job lease, JobStatus status, String error) {
        return jdbc.sql("""
                        UPDATE jobs SET status = :status, locked_by = NULL, locked_at = NULL,
                                        last_error = COALESCE(:error, last_error), updated_at = now()
                        WHERE\s""" + OWNED)
                .param("status", status.name()).param("error", error)
                .param("id", lease.id()).param("worker", lease.lockedBy()).param("attempt", lease.attempts())
                .update() == 1;
    }

    private int owned(String sql, Job lease) {
        return jdbc.sql(sql)
                .param("id", lease.id()).param("worker", lease.lockedBy()).param("attempt", lease.attempts())
                .update();
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
