package com.ticketfactory.integration;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * The factory's view of GitHub. Phase 1: {@code FakeGitHubClient}.
 * Real implementation: {@code GitHubRestClient} (GitHub App or token auth from the environment).
 */
public interface GitHubClient {

    /**
     * Every open issue in {@code repo} carrying {@code label}. Must be complete: implementations page internally, and
     * throw rather than return a partial list (the poller cancels tickets whose issue is missing from it).
     */
    List<Issue> listOpenIssues(String repo, String label);

    /**
     * Opens a PR from {@code head} into {@code base}, or returns the already-open PR for {@code head} (idempotent, so
     * a retry after a crash never opens a duplicate). Must enforce {@link BranchPolicy}.
     */
    PullRequest openPullRequest(PullRequestRequest request);

    PrStatus getPullRequestStatus(String repo, int prNumber);

    void commentOnIssue(String repo, int issueNumber, String body);

    /** @param triggeredAt when the trigger label was (last) applied; re-applying it starts a new attempt */
    record Issue(String repo, int number, String title, String body, Set<String> labels, Instant triggeredAt) {
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
