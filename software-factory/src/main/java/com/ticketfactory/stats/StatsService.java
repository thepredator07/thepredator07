package com.ticketfactory.stats;

import com.ticketfactory.ticket.TicketState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
public class StatsService {

    private final JdbcClient jdbc;

    public StatsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Stats compute() {
        Map<TicketState, Long> byState = new EnumMap<>(TicketState.class);
        for (TicketState s : TicketState.values()) {
            byState.put(s, 0L);
        }
        jdbc.sql("SELECT state, count(*) AS n FROM tickets GROUP BY state")
                .query((rs, i) -> byState.put(TicketState.valueOf(rs.getString("state")), rs.getLong("n")))
                .list();

        return jdbc.sql("""
                        SELECT
                          count(*) AS total,
                          coalesce(avg(duration_ms) FILTER (WHERE state IN ('DONE','FAILED')), -1) AS avg_duration,
                          avg(cost_usd) FILTER (WHERE state IN ('DONE','FAILED','CANCELLED')) AS avg_cost,
                          coalesce(sum(cost_usd), 0) AS total_cost,
                          coalesce(sum(tokens_input + tokens_output), 0) AS total_tokens,
                          coalesce(sum(retries), 0) AS total_retries,
                          avg(cost_usd) FILTER (WHERE state = 'DONE') AS avg_cost_done,
                          avg(cost_usd) FILTER (WHERE state = 'FAILED') AS avg_cost_failed
                        FROM tickets""")
                .query((rs, i) -> {
                    long done = byState.get(TicketState.DONE);
                    long failed = byState.get(TicketState.FAILED);
                    long cancelled = byState.get(TicketState.CANCELLED);
                    long total = rs.getLong("total");
                    double avgDuration = rs.getDouble("avg_duration");
                    return new Stats(
                            total, done, failed, cancelled, total - done - failed - cancelled,
                            done + failed == 0 ? null : (double) done / (done + failed),
                            avgDuration < 0 ? null : Math.round(avgDuration),
                            money(rs.getBigDecimal("avg_cost")),
                            money(rs.getBigDecimal("total_cost")),
                            rs.getLong("total_tokens"),
                            rs.getLong("total_retries"),
                            money(rs.getBigDecimal("avg_cost_done")),
                            money(rs.getBigDecimal("avg_cost_failed")),
                            byState);
                })
                .single();
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(4, RoundingMode.HALF_UP);
    }
}
