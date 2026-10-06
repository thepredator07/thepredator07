package com.ticketfactory.fake;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Default behaviour of every fake, bound from {@code factory.fakes.*}. Per-ticket overrides: {@link FakeScript}. */
@ConfigurationProperties("factory.fakes")
public record FakeProperties(
        @DefaultValue Step sandbox,
        @DefaultValue Agent agent,
        @DefaultValue Step checks,
        @DefaultValue GitHub github) {

    public record Step(
            @DefaultValue("succeed") String mode,
            @DefaultValue("1") int failuresBeforeSuccess) {

        public FakeBehavior behavior() {
            return new FakeBehavior(FakeMode.parse(mode), failuresBeforeSuccess);
        }
    }

    public record Agent(
            @DefaultValue("succeed") String mode,
            @DefaultValue("1") int failuresBeforeSuccess,
            @DefaultValue("6") int turnsPerRun,
            @DefaultValue("4000") long inputTokensPerTurn,
            @DefaultValue("800") long outputTokensPerTurn,
            @DefaultValue("0.12") BigDecimal costPerRunUsd,
            @DefaultValue("PT0S") Duration delay) {

        public FakeBehavior behavior() {
            return new FakeBehavior(FakeMode.parse(mode), failuresBeforeSuccess);
        }
    }

    public record GitHub(
            @DefaultValue("succeed") String mode,
            @DefaultValue("1") int failuresBeforeSuccess,
            @DefaultValue("true") boolean autoApprove) {

        public FakeBehavior behavior() {
            return new FakeBehavior(FakeMode.parse(mode), failuresBeforeSuccess);
        }
    }
}
