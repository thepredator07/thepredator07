package com.ticketfactory.integration.checks;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for {@link SandboxChecksRunner} ({@code factory.integrations=real}), bound from {@code factory.checks.*}.
 * A target repo normally names its own check command in {@code .factory.yml}; these are the fallbacks and limits.
 *
 * @param configFile     where the per-repo settings live, read from the base branch
 * @param defaultCommand used when the repo has no config file; empty means such a repo cannot be worked on
 * @param defaultTimeout used when the config file sets no timeout
 * @param maxTimeout     upper limit for any timeout a repo asks for
 */
@ConfigurationProperties("factory.checks")
public record ChecksProperties(
        @DefaultValue(".factory.yml") String configFile,
        @DefaultValue("") String defaultCommand,
        @DefaultValue("PT10M") Duration defaultTimeout,
        @DefaultValue("PT30M") Duration maxTimeout) {
}
