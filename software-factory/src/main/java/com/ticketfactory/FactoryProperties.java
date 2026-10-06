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
        @DefaultValue("fake") Integrations integrations,
        @DefaultValue Worker worker,
        @DefaultValue Poller poller,
        @DefaultValue Guardrails guardrails) {

    /** Which implementations of GitHubClient, SandboxRunner, AgentRunner and ChecksRunner are wired in. */
    public enum Integrations {
        /** In-memory fakes (Phase 1). Also enables the fake-only dashboard actions and /api/fake. */
        FAKE,
        /** Real GitHub, Docker sandbox, Claude Code and checks (Phase 2). */
        REAL
    }

    public boolean fakeMode() {
        return integrations == Integrations.FAKE;
    }

    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("2") int threads,
            @DefaultValue("PT1S") Duration pollInterval,
            @DefaultValue("PT10M") Duration leaseTimeout,
            @DefaultValue("PT2S") Duration retryBackoff,
            @DefaultValue("PT1M") Duration approvalPollInterval,
            @DefaultValue("5") int maxJobAttempts) {
    }

    /**
     * @param missingPollsBeforeCancel how many polls in a row an issue must be missing (closed or unlabeled) before its
     *                                 running attempt is cancelled. GitHub's issue listing lags writes by a few
     *                                 seconds, so a single miss can be a stale read.
     */
    public record Poller(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("PT10S") Duration interval,
            @DefaultValue("2") int missingPollsBeforeCancel) {
    }

    /** Hard limits per ticket. Breaching any of them fails the ticket. */
    public record Guardrails(
            @DefaultValue("2.00") BigDecimal maxCostUsd,
            @DefaultValue("40") int maxTurns,
            @DefaultValue("PT30M") Duration hardTimeout,
            @DefaultValue("3") int maxRetries) {
    }
}
