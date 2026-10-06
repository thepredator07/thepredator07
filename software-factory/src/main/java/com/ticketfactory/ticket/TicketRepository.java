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

    /** Inserts a ticket in RECEIVED, or returns empty if the issue already has a ticket. */
    public Optional<Long> insertIfAbsent(String repo, int issueNumber, String title, String body, Instant now) {
        return jdbc.sql("""
                        INSERT INTO tickets (repo, issue_number, title, body, state, created_at, updated_at)
                        VALUES (:repo, :issue, :title, :body, 'RECEIVED', :now, :now)
                        ON CONFLICT (repo, issue_number) DO NOTHING
                        RETURNING id""")
                .param("repo", repo)
                .param("issue", issueNumber)
                .param("title", title)
                .param("body", body == null ? "" : body)
                .param("now", Timestamp.from(now))
                .query(Long.class)
                .optional();
    }

    public Optional<Ticket> findById(long id) {
        return jdbc.sql("SELECT * FROM tickets WHERE id = :id").param("id", id).query(TICKET).optional();
    }

    public Ticket get(long id) {
        return findById(id).orElseThrow(() -> new IllegalArgumentException("No ticket " + id));
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
