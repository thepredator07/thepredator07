package com.ticketfactory;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** All factory settings, bound from {@code factory.*}. Every value can be overridden with an env var. */
@ConfigurationProperties("factory")
public record FactoryProperties(
        @DefaultValue("example-org/example-repo") String repo,
        @DefaultValue("factory") String triggerLabel,
        @DefaultValue("main") String baseBranch,
        @DefaultValue Worker worker,
        @DefaultValue Poller poller,
        @DefaultValue Guardrails guardrails) {

    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("2") int threads,
            @DefaultValue("PT1S") Duration pollInterval,
            @DefaultValue("PT10M") Duration leaseTimeout,
            @DefaultValue("PT2S") Duration retryBackoff,
            @DefaultValue("PT5S") Duration approvalPollInterval,
            @DefaultValue("5") int maxJobAttempts) {
    }

    public record Poller(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT10S") Duration interval) {
    }

    /** Hard limits per ticket. Breaching any of them fails the ticket. */
    public record Guardrails(
            @DefaultValue("2.00") BigDecimal maxCostUsd,
            @DefaultValue("40") int maxTurns,
            @DefaultValue("PT30M") Duration hardTimeout,
            @DefaultValue("3") int maxRetries) {
    }
}
