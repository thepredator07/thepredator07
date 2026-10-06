package com.ticketfactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V2 (attempts) applied to a database that has been running Phase 1 (schema V1 with tickets in every kind of state,
 * transitions and jobs) keeps all data, numbers existing tickets as attempt 1, and enforces the new rules.
 */
class MigrationTest extends AbstractIntegrationTest {

    @Autowired PostgreSQLContainer<?> postgres;

    @Test
    void v2KeepsPhase1DataAndEnforcesOneActiveAttemptPerIssue() {
        DriverManagerDataSource ds = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
                postgres.getPassword());
        JdbcClient db = JdbcClient.create(ds);
        db.sql("DROP SCHEMA IF EXISTS phase1 CASCADE").update();

        Flyway.configure().dataSource(ds).schemas("phase1").target("1").load().migrate();
        db.sql("""
                INSERT INTO phase1.tickets (repo, issue_number, title, body, state, cost_usd, turns, retries,
                                            created_at, finished_at, duration_ms)
                VALUES ('acme/app', 1, 'Done one', 'b1', 'DONE', 0.12, 6, 0, now() - interval '2 days', now(), 1000),
                       ('acme/app', 2, 'Failed one', 'b2', 'FAILED', 0.48, 24, 4, now() - interval '1 day', now(), 2000),
                       ('acme/app', 3, 'Running one', 'b3', 'CODING', 0.00, 0, 0, now(), null, null)""").update();
        db.sql("""
                INSERT INTO phase1.ticket_transitions (ticket_id, from_state, to_state, reason)
                SELECT id, null, 'RECEIVED', 'Picked up' FROM phase1.tickets""").update();
        db.sql("INSERT INTO phase1.jobs (ticket_id, status) SELECT id, 'PENDING' FROM phase1.tickets WHERE issue_number = 3")
                .update();

        Flyway.configure().dataSource(ds).schemas("phase1").load().migrate();

        List<Map<String, Object>> rows = db.sql("""
                SELECT issue_number, attempt, title, state, cost_usd, triggered_at = created_at AS trig_ok
                FROM phase1.tickets ORDER BY issue_number""").query().listOfRows();
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.get("attempt")).containsOnly(1);
        assertThat(rows).extracting(r -> r.get("trig_ok")).containsOnly(true);
        assertThat(rows).extracting(r -> r.get("state")).containsExactly("DONE", "FAILED", "CODING");
        assertThat(rows).extracting(r -> r.get("title")).containsExactly("Done one", "Failed one", "Running one");
        assertThat(db.sql("SELECT count(*) FROM phase1.ticket_transitions").query(Long.class).single()).isEqualTo(3);
        assertThat(db.sql("SELECT count(*) FROM phase1.jobs").query(Long.class).single()).isEqualTo(1);

        // Issue 2 failed, so a second attempt is allowed ...
        db.sql("""
                INSERT INTO phase1.tickets (repo, issue_number, attempt, title, state, triggered_at)
                VALUES ('acme/app', 2, 2, 'Failed one', 'RECEIVED', now())""").update();
        // ... but issue 3 is still running, so it is not.
        assertThatThrownBy(() -> db.sql("""
                INSERT INTO phase1.tickets (repo, issue_number, attempt, title, state, triggered_at)
                VALUES ('acme/app', 3, 2, 'Running one', 'RECEIVED', now())""").update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_tickets_one_active_per_issue");
        // Attempt numbers are unique per issue.
        assertThatThrownBy(() -> db.sql("""
                INSERT INTO phase1.tickets (repo, issue_number, attempt, title, state, triggered_at)
                VALUES ('acme/app', 1, 1, 'Dup', 'DONE', now())""").update())
                .isInstanceOf(DataIntegrityViolationException.class);

        db.sql("DROP SCHEMA phase1 CASCADE").update();
    }
}
