package com.ticketfactory.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.stats.Stats;
import com.ticketfactory.stats.StatsService;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.TicketState;
import com.ticketfactory.ticket.Transition;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles({"test", "demo"})
class DemoSeederTest extends AbstractIntegrationTest {

    @Autowired DemoSeeder seeder;
    @Autowired TicketRepository tickets;
    @Autowired StatsService stats;

    @Test
    void everyScenarioPathIsLegal() {
        DemoSeeder.scenarios().forEach(s -> DemoSeeder.validate(s.path()));
    }

    @Test
    void seedsFinishedTicketsWithConsistentHistory() {
        seeder.seedHistory();

        List<Ticket> all = tickets.findAll(null, 100);
        assertThat(all).hasSize(DemoSeeder.scenarios().size()).hasSizeGreaterThanOrEqualTo(18);
        for (Ticket t : all) {
            assertThat(t.state().isTerminal()).isTrue();
            List<Transition> history = tickets.history(t.id());
            assertThat(history.getFirst().toState()).isEqualTo(TicketState.RECEIVED);
            assertThat(history.getLast().toState()).isEqualTo(t.state());
            for (int i = 1; i < history.size(); i++) {
                assertThat(history.get(i).fromState()).isEqualTo(history.get(i - 1).toState());
                assertThat(history.get(i).createdAt()).isAfterOrEqualTo(history.get(i - 1).createdAt());
            }
            assertThat(t.durationMs()).isNotNull();
        }
        Stats s = stats.compute();
        assertThat(s.done()).isEqualTo(13);
        assertThat(s.failed()).isEqualTo(4);
        assertThat(s.cancelled()).isEqualTo(2);
        assertThat(s.successRate()).isEqualTo(13.0 / 17);

        // Issue #14 failed on attempt 1 and was fixed on attempt 2.
        List<Ticket> flaky = tickets.attemptsFor(all.getFirst().repo(), 14);
        assertThat(flaky).extracting(Ticket::attempt).containsExactly(2, 1);
        assertThat(flaky).extracting(Ticket::state).containsExactly(TicketState.DONE, TicketState.FAILED);
        assertThat(flaky.get(0).prNumber()).isNotEqualTo(flaky.get(1).prNumber());
        assertThat(s.totalCostUsd()).isPositive();
    }
}
