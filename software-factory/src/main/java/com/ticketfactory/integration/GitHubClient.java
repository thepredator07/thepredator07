package com.ticketfactory.integration;

import java.util.List;
import java.util.Set;

/**
 * The factory's view of GitHub. Phase 1: {@code FakeGitHubClient}.
 * TODO(phase-2): REST/GraphQL implementation using a GitHub App token from the environment.
 */
public interface GitHubClient {

    List<Issue> listOpenIssues(String repo, String label);

    /** Opens a PR from {@code head} into {@code base}. Implementations must refuse to target or push to main. */
    PullRequest openPullRequest(PullRequestRequest request);

    PrStatus getPullRequestStatus(String repo, int prNumber);

    void commentOnIssue(String repo, int issueNumber, String body);

    record Issue(String repo, int number, String title, String body, Set<String> labels) {
    }

    record PullRequestRequest(String repo, int issueNumber, String head, String base, String title, String body) {
    }

    record PullRequest(int number, String url, String head, String base) {
    }

    enum PrStatus {
        /** Review requested, nobody has acted yet. */
        PENDING,
        /** Approved (or merged) by a human. */
        APPROVED,
        /** Closed without merging. */
        CLOSED
    }
}
