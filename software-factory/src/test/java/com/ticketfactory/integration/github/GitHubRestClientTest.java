package com.ticketfactory.integration.github;

import static com.ticketfactory.integration.github.SimulatedGitHub.REPO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketfactory.integration.GitHubClient.Issue;
import com.ticketfactory.integration.GitHubClient.PrStatus;
import com.ticketfactory.integration.GitHubClient.PullRequest;
import com.ticketfactory.integration.GitHubClient.PullRequestRequest;
import com.ticketfactory.integration.RateLimitedException;
import com.ticketfactory.integration.StepFailedException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behavior of the real client beyond the shared contract: auth, paging, caching, permissions, errors, reviews. */
class GitHubRestClientTest {

    private GitHubApiSimulator sim;
    private GitHubRestClient client;

    @BeforeEach
    void start() throws Exception {
        sim = new GitHubApiSimulator(REPO, SimulatedGitHub.TOKEN);
        sim.setPermission("maintainer", "write");
        sim.setPermission("owner", "admin");
        sim.setPermission("triager", "triage");
        client = SimulatedGitHub.client(sim, 100);
    }

    @AfterEach
    void stop() {
        sim.close();
    }

    private List<Integer> listed() {
        return client.listOpenIssues(REPO, "factory").stream().map(Issue::number).toList();
    }

    private PullRequest openPr(int issue, String head) {
        return client.openPullRequest(new PullRequestRequest(REPO, issue, head, "main", "t", "Closes #" + issue));
    }

    // ---- requests ----

    @Test
    void sendsBearerTokenAndApiVersionOnEveryCall() {
        sim.createIssue("x", "", "maintainer", "factory");
        listed();
        assertThat(sim.requests()).isNotEmpty()
                .allSatisfy(r -> assertThat(r.authorization()).isEqualTo("Bearer test-token"));
    }

    @Test
    void wrongTokenSurfacesAsAClearError() {
        GitHubRestClient bad = com.ticketfactory.integration.real.RealIntegrationsConfig.createGitHubClient(
                new GitHubProperties(sim.url(), "wrong", null, null, null, GitHubProperties.Permission.WRITE, 100,
                        Duration.ofSeconds(5), Duration.ofMinutes(10)), java.time.Clock.systemUTC());
        assertThatThrownBy(() -> bad.listOpenIssues(REPO, "factory"))
                .isInstanceOf(GitHubApiException.class).hasMessageContaining("401").hasMessageContaining("Bad credentials");
    }

    // ---- listing ----

    @Test
    void followsPagingLinksToTheEnd() {
        GitHubRestClient small = SimulatedGitHub.client(sim, 2);
        for (int i = 0; i < 7; i++) {
            sim.createIssue("Issue " + i, "", "maintainer", "factory");
        }
        assertThat(small.listOpenIssues(REPO, "factory")).hasSize(7);
        assertThat(sim.requests()).filteredOn(r -> r.pathAndQuery().startsWith("/repos/acme/app/issues?"))
                .as("4 pages of 2").hasSize(4);
    }

    @Test
    void skipsPullRequestsReturnedByTheIssuesApi() {
        int issue = sim.createIssue("Real issue", "", "maintainer", "factory");
        sim.createPullRequestIssue("A PR with the label", "factory");
        assertThat(listed()).containsExactly(issue);
    }

    @Test
    void nullBodyBecomesEmptyString() {
        sim.createIssue("No body", null, "maintainer", "factory");
        assertThat(client.listOpenIssues(REPO, "factory").getFirst().body()).isEmpty();
    }

    @Test
    void unchangedListingIsServedFromTheEtagCache() {
        sim.createIssue("x", "", "maintainer", "factory");
        listed();
        int before = sim.requests().size();

        listed();

        List<GitHubApiSimulator.Request> second = sim.requests().subList(before, sim.requests().size());
        assertThat(second).as("only the conditional listing; events and permission come from cache").hasSize(1);
        assertThat(second.getFirst().ifNoneMatch()).isNotNull();
    }

    // ---- who may trigger, and when ----

    @Test
    void ignoresIssuesLabeledBySomeoneWithoutWriteAccess() {
        int byMaintainer = sim.createIssue("ok", "", "maintainer", "factory");
        int byOwner = sim.createIssue("ok too", "", "owner", "factory");
        int byTriager = sim.createIssue("triage only", "", "triager", "factory");
        int byStranger = sim.createIssue("drive-by", "", "stranger", "factory");

        assertThat(listed()).containsExactlyInAnyOrder(byMaintainer, byOwner).doesNotContain(byTriager, byStranger);
    }

    @Test
    void theLatestLabelerCountsNotTheFirst() {
        int n = sim.createIssue("x", "", "maintainer", "factory");
        sim.unlabel(n, "factory", "maintainer");
        sim.label(n, "factory", "stranger");
        assertThat(listed()).doesNotContain(n);
    }

    @Test
    void triggerTimeIsWhenTheLabelWasLastApplied() {
        int n = sim.createIssue("x", "", "maintainer", "factory");
        Instant first = client.listOpenIssues(REPO, "factory").getFirst().triggeredAt();

        sim.unlabel(n, "factory", "maintainer");
        sim.label(n, "factory", "maintainer");
        Instant second = client.listOpenIssues(REPO, "factory").getFirst().triggeredAt();

        assertThat(second).isAfter(first);
    }

    @Test
    void findsTheLabeledEventEvenWhenItIsNotOnTheLastEventsPage() {
        int n = sim.createIssue("busy issue", "", "maintainer", "factory");
        sim.addNoiseEvents(n, 250); // 3 pages of 100 events after the labeling
        Issue issue = client.listOpenIssues(REPO, "factory").getFirst();
        assertThat(issue.number()).isEqualTo(n);
        assertThat(issue.triggeredAt()).isNotNull();
    }

    // ---- pull requests ----

    @Test
    void prCreationRaceReturnsTheOtherPr() {
        int issue = sim.createIssue("x", "", "maintainer", "factory");
        PullRequest existing = openPr(issue, "factory/1");
        // Our lookup misses it (as if the other side created it a moment later), then create says "already exists".
        sim.failNext("/repos/acme/app/pulls\\?state=open", 200, Map.of(), "[]");

        PullRequest pr = openPr(issue, "factory/1");

        assertThat(pr.number()).isEqualTo(existing.number());
        assertThat(sim.pullRequestCount()).isEqualTo(1);
    }

    @Test
    void reviewStateDecidesApproval() {
        int issue = sim.createIssue("x", "", "maintainer", "factory");
        int pr = openPr(issue, "factory/2").number();

        assertThat(client.getPullRequestStatus(REPO, pr)).isEqualTo(PrStatus.PENDING);
        sim.review(pr, "alice", "COMMENTED");
        assertThat(client.getPullRequestStatus(REPO, pr)).as("a comment is not a decision").isEqualTo(PrStatus.PENDING);
        sim.review(pr, "alice", "APPROVED");
        sim.review(pr, "bob", "CHANGES_REQUESTED");
        assertThat(client.getPullRequestStatus(REPO, pr)).as("outstanding changes requested").isEqualTo(PrStatus.PENDING);
        sim.review(pr, "bob", "DISMISSED");
        assertThat(client.getPullRequestStatus(REPO, pr)).isEqualTo(PrStatus.APPROVED);
    }

    @Test
    void mergedCountsAsApprovedAndClosedAsClosed() {
        int issue = sim.createIssue("x", "", "maintainer", "factory");
        int merged = openPr(issue, "factory/3").number();
        int closed = openPr(issue, "factory/4").number();
        sim.merge(merged);
        sim.closePullRequest(closed);
        assertThat(client.getPullRequestStatus(REPO, merged)).isEqualTo(PrStatus.APPROVED);
        assertThat(client.getPullRequestStatus(REPO, closed)).isEqualTo(PrStatus.CLOSED);
        assertThat(client.getPullRequestStatus(REPO, 99_999)).as("deleted / unknown").isEqualTo(PrStatus.CLOSED);
    }

    @Test
    void commentsArePosted() {
        int issue = sim.createIssue("x", "", "maintainer", "factory");
        client.commentOnIssue(REPO, issue, "Opened PR #5.");
        assertThat(sim.comments(issue)).containsExactly("Opened PR #5.");
    }

    // ---- errors and rate limits ----

    @Test
    void primaryRateLimitWaitsUntilReset() {
        long reset = Instant.now().getEpochSecond() + 600;
        sim.failNext("/issues\\?", 403, Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", Long.toString(reset)),
                "{\"message\":\"API rate limit exceeded\"}");
        assertThatThrownBy(this::listed)
                .isInstanceOfSatisfying(RateLimitedException.class, e ->
                        assertThat(e.retryAfter()).isBetween(Duration.ofSeconds(590), Duration.ofSeconds(601)));
    }

    @Test
    void secondaryRateLimitHonorsRetryAfter() {
        sim.failNext("/pulls", 429, Map.of("Retry-After", "42"), "{\"message\":\"secondary rate limit\"}");
        int issue = sim.createIssue("x", "", "maintainer", "factory");
        assertThatThrownBy(() -> openPr(issue, "factory/5"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.retryAfter()).isEqualTo(Duration.ofSeconds(42)));
    }

    @Test
    void forbiddenWithoutRateLimitHeadersIsAPlainApiError() {
        sim.failNext("/issues\\?", 403, Map.of(), "{\"message\":\"Resource not accessible by integration\"}");
        assertThatThrownBy(this::listed).isInstanceOf(GitHubApiException.class)
                .isNotInstanceOf(RateLimitedException.class).hasMessageContaining("not accessible");
    }

    @Test
    void serverErrorsAreRetryableStepFailures() {
        sim.failNext("/issues\\?", 502, Map.of(), "{\"message\":\"Bad Gateway\"}");
        assertThatThrownBy(this::listed).isInstanceOf(StepFailedException.class)
                .isNotInstanceOf(GitHubApiException.class).hasMessageContaining("502");
    }

    @Test
    void aFailureOnALaterPageFailsTheWholeListingInsteadOfReturningPartOfIt() {
        GitHubRestClient small = SimulatedGitHub.client(sim, 2);
        for (int i = 0; i < 5; i++) {
            sim.createIssue("Issue " + i, "", "maintainer", "factory");
        }
        sim.failNext("/issues\\?.*page=2", 503, Map.of(), "{\"message\":\"unavailable\"}");
        assertThatThrownBy(() -> small.listOpenIssues(REPO, "factory")).isInstanceOf(StepFailedException.class);
    }
}
