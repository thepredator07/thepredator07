package com.ticketfactory.metrics;

import com.ticketfactory.ticket.TicketState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * The factory's own metrics (Prometheus names in brackets). Queue depth and tickets by state are gauges read from the
 * database ({@link QueueGauges}); everything here is counted where it happens.
 *
 * <ul>
 *   <li>{@code factory_tickets_finished_total{outcome}}: tickets reaching DONE, FAILED or CANCELLED</li>
 *   <li>{@code factory_guardrail_trips_total{guardrail}}: cost, turns, timeout, budget</li>
 *   <li>{@code factory_lease_losses_total}, {@code factory_worker_errors_total}</li>
 *   <li>{@code factory_step_seconds{step}}: time per pipeline step (state)</li>
 *   <li>{@code factory_agent_run_seconds{success}}, {@code factory_agent_cost_usd_total},
 *       {@code factory_agent_tokens_total{direction}}, {@code factory_agent_turns_total}</li>
 * </ul>
 */
@Component
public class FactoryMetrics {

    /** For code constructed outside Spring (tests): counts into a private registry nobody reads. */
    public static final FactoryMetrics NOOP = new FactoryMetrics(new SimpleMeterRegistry());

    private final MeterRegistry registry;

    public FactoryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void ticketFinished(TicketState outcome) {
        Counter.builder("factory.tickets.finished").description("Tickets that reached a final state")
                .tag("outcome", outcome.name()).register(registry).increment();
    }

    public void guardrailTripped(String guardrail) {
        Counter.builder("factory.guardrail.trips").description("Tickets failed by a per-ticket limit")
                .tag("guardrail", guardrail).register(registry).increment();
    }

    public void leaseLost() {
        Counter.builder("factory.lease.losses").description("Jobs whose lease was lost while a worker ran them")
                .register(registry).increment();
    }

    public void workerError() {
        Counter.builder("factory.worker.errors").description("Unexpected errors while running a job")
                .register(registry).increment();
    }

    public void stepTook(TicketState step, Duration took) {
        Timer.builder("factory.step").description("Time spent in one pipeline step").tag("step", step.name())
                .register(registry).record(took);
    }

    public void agentRun(Duration took, boolean success, BigDecimal costUsd, long inputTokens, long outputTokens,
                         int turns) {
        Timer.builder("factory.agent.run").description("Agent runs").tag("success", Boolean.toString(success))
                .register(registry).record(took);
        Counter.builder("factory.agent.cost.usd").description("Agent spend as reported by the agent")
                .register(registry).increment(costUsd.doubleValue());
        Counter.builder("factory.agent.tokens").tag("direction", "input").register(registry).increment(inputTokens);
        Counter.builder("factory.agent.tokens").tag("direction", "output").register(registry).increment(outputTokens);
        Counter.builder("factory.agent.turns").register(registry).increment(turns);
    }
}
