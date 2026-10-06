package com.ticketfactory.ticket;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class TicketRepository {

    private static final RowMapper<Ticket> TICKET = TicketRepository::mapTicket;
    private static final RowMapper<Transition> TRANSITION = (rs, i) -> new Transition(
            rs.getLong("id"),
            rs.getLong("ticket_id"),
            rs.getString("from_state") == null ? null : TicketState.valueOf(rs.getString("from_state")),
            TicketState.valueOf(rs.getString("to_state")),
            rs.getString("reason"),
            instant(rs, "created_at"));

    private final JdbcClient jdbc;

    public TicketRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a new attempt (state RECEIVED) for an issue and returns its id. Returns empty, inserting nothing, when
     * the issue already has an unfinished attempt, or when the trigger was already used by an earlier attempt
     * ({@code triggeredAt} is not newer than the latest attempt's). Re-applying the label is what starts a new attempt.
     */
    public Optional<Long> insertAttempt(String repo, int issueNumber, String title, String body, Instant triggeredAt,
                                        Instant now) {
        // An aggregate without GROUP BY yields exactly one row even when the issue has no attempts yet;
        // HAVING then decides whether that row is inserted. ON CONFLICT covers a concurrent insert of the same attempt.
        return jdbc.sql("""
                        INSERT INTO tickets (repo, issue_number, attempt, title, body, state, triggered_at,
                                             created_at, updated_at)
                        SELECT :repo, :issue, COALESCE(MAX(t.attempt), 0) + 1, :title, :body, 'RECEIVED',
                               :triggeredAt, :now, :now
                        FROM tickets t
                        WHERE t.repo = :repo AND t.issue_number = :issue
                        HAVING COUNT(*) FILTER (WHERE t.state NOT IN ('DONE','FAILED','CANCELLED')) = 0
                           AND (MAX(t.triggered_at) IS NULL OR MAX(t.triggered_at) < :triggeredAt)
                        ON CONFLICT DO NOTHING
                        RETURNING id""")
                .param("repo", repo)
                .param("issue", issueNumber)
                .param("title", title)
                .param("body", body == null ? "" : body)
                .param("triggeredAt", Timestamp.from(triggeredAt))
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .optional();
    }

    /** All attempts at one issue, newest first. */
    public List<Ticket> attemptsFor(String repo, int issueNumber) {
        return jdbc.sql("SELECT * FROM tickets WHERE repo = :repo AND issue_number = :issue ORDER BY attempt DESC")
                .param("repo", repo).param("issue", issueNumber).query(TICKET).list();
    }

    /** Unfinished attempts in a repo (for reconciling against the issue tracker). */
    public List<Ticket> findActive(String repo) {
        return jdbc.sql("""
                        SELECT * FROM tickets WHERE repo = :repo AND state NOT IN ('DONE','FAILED','CANCELLED')
                        ORDER BY id""")
                .param("repo", repo).query(TICKET).list();
    }

    /** The ticket waiting for approval on this PR, if any (webhooks find their ticket this way). */
    public Optional<Ticket> findAwaitingApproval(String repo, int prNumber) {
        return jdbc.sql("SELECT * FROM tickets WHERE repo = :repo AND pr_number = :pr AND state = 'AWAITING_APPROVAL'")
                .param("repo", repo).param("pr", prNumber).query(TICKET).optional();
    }

    public Optional<Ticket> findById(long id) {
        return jdbc.sql("SELECT * FROM tickets WHERE id = :id").param("id", id).query(TICKET).optional();
    }

    public Ticket get(long id) {
        return findById(id).orElseThrow(() -> new IllegalArgumentException("No ticket " + id));
    }

    /** Newest first, {@code limit} rows starting at {@code offset}, optionally only one state. */
    public List<Ticket> findPage(TicketState stateFilter, int offset, int limit) {
        if (stateFilter == null) {
            return jdbc.sql("SELECT * FROM tickets ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
                    .param("limit", limit).param("offset", offset).query(TICKET).list();
        }
        return jdbc.sql("SELECT * FROM tickets WHERE state = :state ORDER BY created_at DESC, id DESC"
                        + " LIMIT :limit OFFSET :offset")
                .param("state", stateFilter.name()).param("limit", limit).param("offset", offset).query(TICKET).list();
    }

    public List<Ticket> findAll(TicketState stateFilter, int limit) {
        if (stateFilter == null) {
            return jdbc.sql("SELECT * FROM tickets ORDER BY created_at DESC, id DESC LIMIT :limit")
                    .param("limit", limit).query(TICKET).list();
        }
        return jdbc.sql("SELECT * FROM tickets WHERE state = :state ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("state", stateFilter.name()).param("limit", limit).query(TICKET).list();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM tickets").query(Long.class).single();
    }

    public List<Transition> history(long ticketId) {
        return jdbc.sql("SELECT * FROM ticket_transitions WHERE ticket_id = :id ORDER BY created_at, id")
                .param("id", ticketId).query(TRANSITION).list();
    }

    /** Guarded update: only succeeds if the row is still in {@code from}. Returns rows updated (0 or 1). */
    int updateState(long id, TicketState from, TicketState to, Instant now) {
        return jdbc.sql("UPDATE tickets SET state = :to, updated_at = :now WHERE id = :id AND state = :from")
                .param("to", to.name()).param("from", from.name()).param("id", id).param("now", Timestamp.from(now))
                .update();
    }

    void insertTransition(long ticketId, TicketState from, TicketState to, String reason, Instant at) {
        jdbc.sql("""
                        INSERT INTO ticket_transitions (ticket_id, from_state, to_state, reason, created_at)
                        VALUES (:id, :from, :to, :reason, :at)""")
                .param("id", ticketId)
                .param("from", from == null ? null : from.name())
                .param("to", to.name())
                .param("reason", reason)
                .param("at", Timestamp.from(at))
                .update();
    }

    public void markStarted(long id, Instant now) {
        jdbc.sql("UPDATE tickets SET started_at = COALESCE(started_at, :now), updated_at = :now WHERE id = :id")
                .param("id", id).param("now", Timestamp.from(now)).update();
    }

    public void markFinished(long id, Instant now, String failureReason) {
        jdbc.sql("""
                        UPDATE tickets SET finished_at = :now, updated_at = :now,
                            failure_reason = COALESCE(:reason, failure_reason),
                            duration_ms = (EXTRACT(EPOCH FROM (:now - COALESCE(started_at, created_at))) * 1000)::BIGINT
                        WHERE id = :id""")
                .param("id", id).param("now", Timestamp.from(now)).param("reason", failureReason).update();
    }

    public void recordUsage(long id, long inputTokens, long outputTokens, BigDecimal cost, int turns) {
        jdbc.sql("""
                        UPDATE tickets SET tokens_input = tokens_input + :in, tokens_output = tokens_output + :out,
                            cost_usd = cost_usd + :cost, turns = turns + :turns, updated_at = now()
                        WHERE id = :id""")
                .param("id", id).param("in", inputTokens).param("out", outputTokens).param("cost", cost)
                .param("turns", turns).update();
    }

    public int incrementRetries(long id) {
        return jdbc.sql("UPDATE tickets SET retries = retries + 1, updated_at = now() WHERE id = :id RETURNING retries")
                .param("id", id).query(Integer.class).single();
    }

    public void setSandbox(long id, String sandboxId, String branchName) {
        jdbc.sql("UPDATE tickets SET sandbox_id = :sb, branch_name = :branch, updated_at = now() WHERE id = :id")
                .param("id", id).param("sb", sandboxId).param("branch", branchName).update();
    }

    public void setPullRequest(long id, int prNumber, String prUrl) {
        jdbc.sql("UPDATE tickets SET pr_number = :n, pr_url = :url, updated_at = now() WHERE id = :id")
                .param("id", id).param("n", prNumber).param("url", prUrl).update();
    }

    public void setFeedback(long id, String feedback) {
        jdbc.sql("UPDATE tickets SET last_feedback = :f, updated_at = now() WHERE id = :id")
                .param("id", id).param("f", feedback).update();
    }

    private static Ticket mapTicket(ResultSet rs, int rowNum) throws SQLException {
        long duration = rs.getLong("duration_ms");
        Long durationMs = rs.wasNull() ? null : duration;
        int pr = rs.getInt("pr_number");
        Integer prNumber = rs.wasNull() ? null : pr;
        return new Ticket(
                rs.getLong("id"),
                rs.getString("repo"),
                rs.getInt("issue_number"),
                rs.getInt("attempt"),
                rs.getString("title"),
                rs.getString("body"),
                TicketState.valueOf(rs.getString("state")),
                rs.getString("branch_name"),
                rs.getString("sandbox_id"),
                prNumber,
                rs.getString("pr_url"),
                rs.getString("last_feedback"),
                rs.getString("failure_reason"),
                rs.getLong("tokens_input"),
                rs.getLong("tokens_output"),
                rs.getBigDecimal("cost_usd"),
                rs.getInt("turns"),
                rs.getInt("retries"),
                instant(rs, "triggered_at"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                durationMs);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
