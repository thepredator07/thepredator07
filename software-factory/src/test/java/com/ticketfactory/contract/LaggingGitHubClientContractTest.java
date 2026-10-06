package com.ticketfactory.contract;

import com.ticketfactory.integration.GitHubClient;
import com.ticketfactory.integration.github.GitHubApiSimulator;
import com.ticketfactory.integration.github.GitHubProperties;
import com.ticketfactory.integration.real.RealIntegrationsConfig;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * The contract against a simulator whose issue listing lags behind writes (two stale reads after each change), as
 * GitHub's did in the first live run. Proves the contract's listing retry works before it meets real GitHub again.
 */
class LaggingGitHubClientContractTest extends GitHubClientContract {

    private GitHubApiSimulator sim;
    private GitHubClient client;

    @BeforeEach
    void start() throws Exception {
        sim = new GitHubApiSimulator("acme/app", "lag-token");
        sim.setPermission("maintainer", "write");
        sim.setListingLag(2);
        client = RealIntegrationsConfig.createGitHubClient(new GitHubProperties(sim.url(), "lag-token", null, null,
                null, GitHubProperties.Permission.WRITE, 100, Duration.ofSeconds(5), Duration.ofMinutes(10)),
                Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    @Override
    protected Duration listingConsistencyWait() {
        return Duration.ofSeconds(20);
    }

    @Override
    protected int manyIssues() {
        return 20;
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
