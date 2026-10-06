package com.ticketfactory.integration.phase2;

/**
 * TODO(phase-2): Real GitHub API client (issues, branches, PRs, reviews), authenticated with GITHUB_TOKEN or a GitHub App.
 *
 * <p>Deliberately not implemented and not a Spring bean in Phase 1. Implement
 * {@link com.ticketfactory.integration.GitHubClient} and register it in place of the fake
 * (see {@code com.ticketfactory.fake.FakeIntegrationsConfig}).
 */
final class GitHubRestClient {

    private GitHubRestClient() {
        throw new UnsupportedOperationException("Phase 2: GitHubRestClient is not implemented yet");
    }
}
