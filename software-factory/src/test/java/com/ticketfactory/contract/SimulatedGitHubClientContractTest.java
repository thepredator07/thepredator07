package com.ticketfactory.contract;

import com.ticketfactory.integration.GitHubClient;
import com.ticketfactory.integration.github.GitHubApiSimulator;
import com.ticketfactory.integration.github.GitHubProperties;
import com.ticketfactory.integration.real.RealIntegrationsConfig;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** The real {@code GitHubRestClient}, over real HTTP, against the GitHub API simulator. Runs on every build. */
class SimulatedGitHubClientContractTest extends GitHubClientContract {

    private GitHubApiSimulator sim;
    private GitHubClient client;

    @BeforeEach
    void start() throws Exception {
        sim = new GitHubApiSimulator("acme/app", "contract-token");
        sim.setPermission("maintainer", "write");
        client = RealIntegrationsConfig.createGitHubClient(new GitHubProperties(sim.url(), "contract-token", null,
                null, null, GitHubProperties.Permission.WRITE, 100, Duration.ofSeconds(5), Duration.ofMinutes(10)),
                Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    @Override
    protected GitHubClient client() {
        return client;
    }

    @Override
    protected String repo() {
        return "acme/app";
    }

    @Override
    protected int openIssue(String title, String body, String... labels) {
        return sim.createIssue(title, body, "maintainer", labels);
    }

    @Override
    protected void approve(int prNumber) {
        sim.review(prNumber, "reviewer", "APPROVED");
    }
}
