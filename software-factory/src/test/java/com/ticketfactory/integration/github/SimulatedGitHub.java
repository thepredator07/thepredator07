package com.ticketfactory.integration.github;

import java.time.Clock;
import java.time.Duration;

/** Test helper: a simulator plus a real {@link GitHubRestClient} pointed at it with token auth. */
final class SimulatedGitHub {

    static final String REPO = "acme/app";
    static final String TOKEN = "test-token";

    private SimulatedGitHub() {
    }

    static GitHubProperties props(String apiUrl, int pageSize) {
        return new GitHubProperties(apiUrl, TOKEN, null, null, null, GitHubProperties.Permission.WRITE, pageSize,
                Duration.ofSeconds(5), Duration.ofMinutes(10));
    }

    static GitHubRestClient client(GitHubApiSimulator sim, int pageSize) {
        return com.ticketfactory.integration.real.RealIntegrationsConfig.createGitHubClient(
                props(sim.url(), pageSize), Clock.systemUTC());
    }
}
