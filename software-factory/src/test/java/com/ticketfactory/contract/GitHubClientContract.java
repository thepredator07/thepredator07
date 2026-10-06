package com.ticketfactory.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketfactory.integration.GitHubClient;
import com.ticketfactory.integration.GitHubClient.Issue;
import com.ticketfactory.integration.GitHubClient.PrStatus;
import com.ticketfactory.integration.GitHubClient.PullRequest;
import com.ticketfactory.integration.GitHubClient.PullRequestRequest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What every {@link GitHubClient} must do. Subclass it once per implementation: the fake runs it on every build;
 * the real client (M2) runs it against WireMock on every build and a live test repo nightly.
 */
public abstract class GitHubClientContract {

    protected static final String LABEL = "factory";

    protected abstract GitHubClient client();

    protected abstract String repo();

    /** Opens a new issue in {@link #repo()} and returns its number. */
    protected abstract int openIssue(String title, String body, String... labels);

    /** A human approves the PR. */
    protected abstract void approve(int prNumber);

    /** Ticket ids only need to be unique within a test run; real implementations can use a random base. */
    protected long newTicketId() {
        return System.nanoTime() % 1_000_000_000L;
    }

    /**
     * Makes {@code head} a branch a PR can be opened from. Simulated GitHub doesn't care; real GitHub needs the branch
     * to exist with at least one commit beyond base.
     */
    protected void prepareBranch(String head) {
    }

    /** How many issues to create for the paging test. Real implementations can lower it to save API calls. */
    protected int manyIssues() {
        return 150;
    }

    private String newBranch() {
        String head = "factory/" + newTicketId();
        prepareBranch(head);
        return head;
    }

    private PullRequestRequest pr(int issue, String head) {
        return new PullRequestRequest(repo(), issue, head, "main", "[factory] contract test", "Closes #" + issue);
    }

    @Test
    void listsOnlyOpenIssuesCarryingTheLabel() {
        int wanted = openIssue("Wanted", "body", LABEL, "bug");
        int unlabeled = openIssue("Unlabeled", "body", "bug");

        List<Integer> numbers = client().listOpenIssues(repo(), LABEL).stream().map(Issue::number).toList();

        assertThat(numbers).contains(wanted).doesNotContain(unlabeled);
    }

    @Test
    void issueCarriesTitleBodyLabelsAndTriggerTime() {
        Instant before = Instant.now().minusSeconds(5);
        int n = openIssue("Add CSV export", "Users want CSV.", LABEL);

        Issue issue = client().listOpenIssues(repo(), LABEL).stream().filter(i -> i.number() == n).findFirst()
                .orElseThrow();

        assertThat(issue.repo()).isEqualTo(repo());
        assertThat(issue.title()).isEqualTo("Add CSV export");
        assertThat(issue.body()).isEqualTo("Users want CSV.");
        assertThat(issue.labels()).contains(LABEL);
        assertThat(issue.triggeredAt()).isNotNull().isAfter(before);
    }

    @Test
    void listingIsCompleteBeyondOnePage() {
        int count = manyIssues();
        for (int i = 0; i < count; i++) {
            openIssue("Bulk " + i, "", LABEL);
        }
        assertThat(client().listOpenIssues(repo(), LABEL)).hasSizeGreaterThanOrEqualTo(count);
    }

    @Test
    void opensAPullRequestFromTheFactoryBranchIntoBase() {
        int issue = openIssue("PR me", "", LABEL);
        String head = newBranch();

        PullRequest pr = client().openPullRequest(pr(issue, head));

        assertThat(pr.number()).isPositive();
        assertThat(pr.url()).contains("/pull/");
        assertThat(pr.head()).isEqualTo(head);
        assertThat(pr.base()).isEqualTo("main");
    }

    @Test
    void openingTheSamePullRequestAgainReturnsTheExistingOne() {
        int issue = openIssue("Retry me", "", LABEL);
        String head = newBranch();

        PullRequest first = client().openPullRequest(pr(issue, head));
        PullRequest again = client().openPullRequest(pr(issue, head));

        assertThat(again.number()).isEqualTo(first.number());
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "master", "feature/sneaky", "factory/"})
    void refusesBranchesOutsideTheFactoryNamespace(String head) {
        int issue = openIssue("Unsafe", "", LABEL);
        assertThatThrownBy(() -> client().openPullRequest(pr(issue, head)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void newPullRequestIsPendingUntilApproved() {
        int issue = openIssue("Review me", "", LABEL);
        PullRequest pr = client().openPullRequest(pr(issue, newBranch()));

        assertThat(client().getPullRequestStatus(repo(), pr.number())).isEqualTo(PrStatus.PENDING);
        approve(pr.number());
        assertThat(client().getPullRequestStatus(repo(), pr.number())).isEqualTo(PrStatus.APPROVED);
    }

    @Test
    void commentingOnAnIssueSucceeds() {
        int issue = openIssue("Comment on me", "", LABEL);
        assertThatCode(() -> client().commentOnIssue(repo(), issue, "Opened a PR.")).doesNotThrowAnyException();
    }
}
