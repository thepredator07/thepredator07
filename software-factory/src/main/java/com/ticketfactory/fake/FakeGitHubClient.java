package com.ticketfactory.fake;

import com.ticketfactory.integration.BranchPolicy;
import com.ticketfactory.integration.GitHubClient;
import com.ticketfactory.integration.StepFailedException;
import com.ticketfactory.integration.TicketContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** In-memory GitHub: issues you add, PRs the factory opens, comments it posts. */
public class FakeGitHubClient extends AbstractFake implements GitHubClient {

    private final boolean autoApprove;
    private final Map<String, Issue> issues = new ConcurrentHashMap<>();
    private final Map<Integer, OpenedPr> prs = new ConcurrentHashMap<>();
    private final List<String> comments = new CopyOnWriteArrayList<>();
    private final AtomicInteger prNumbers = new AtomicInteger(1000);
    private volatile boolean listingFails;
    private final AtomicInteger rateLimitedCalls = new AtomicInteger();
    private volatile java.time.Duration rateLimitWait = java.time.Duration.ZERO;
    private final java.util.concurrent.atomic.AtomicReference<Instant> lastTrigger =
            new java.util.concurrent.atomic.AtomicReference<>();

    public FakeGitHubClient(FakeProperties.GitHub config) {
        super("github", config.behavior());
        this.autoApprove = config.autoApprove();
    }

    public record OpenedPr(PullRequest pr, String repo, String title, String body, PrStatus status) {
        OpenedPr withStatus(PrStatus s) {
            return new OpenedPr(pr, repo, title, body, s);
        }
    }

    // ---- test / demo controls ----

    /** Opens an issue (or replaces it). The trigger time is now. */
    public void addIssue(String repo, int number, String title, String body, String... labels) {
        issues.put(key(repo, number), new Issue(repo, number, title, body, Set.of(labels), now()));
    }

    /** Removes and re-applies {@code label}: a new trigger, so a finished issue gets a new attempt. */
    public void relabel(String repo, int number, String label) {
        issues.computeIfPresent(key(repo, number), (k, i) -> {
            Set<String> labels = new java.util.HashSet<>(i.labels());
            labels.add(label);
            return new Issue(i.repo(), i.number(), i.title(), i.body(), Set.copyOf(labels), now());
        });
    }

    public void removeLabel(String repo, int number, String label) {
        issues.computeIfPresent(key(repo, number), (k, i) -> {
            Set<String> labels = new java.util.HashSet<>(i.labels());
            labels.remove(label);
            return new Issue(i.repo(), i.number(), i.title(), i.body(), Set.copyOf(labels), i.triggeredAt());
        });
    }

    public void editIssue(String repo, int number, String title, String body) {
        issues.computeIfPresent(key(repo, number),
                (k, i) -> new Issue(i.repo(), i.number(), title, body, i.labels(), i.triggeredAt()));
    }

    public void closeIssue(String repo, int number) {
        issues.remove(key(repo, number));
    }

    private static String key(String repo, int number) {
        return repo + "#" + number;
    }

    /** Microsecond precision, like GitHub timestamps stored in Postgres; strictly increasing per fake. */
    private Instant now() {
        Instant candidate = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return lastTrigger.updateAndGet(prev ->
                prev == null || candidate.isAfter(prev) ? candidate : prev.plus(1, java.time.temporal.ChronoUnit.MICROS));
    }

    public void setPullRequestStatus(int prNumber, PrStatus status) {
        prs.computeIfPresent(prNumber, (n, p) -> p.withStatus(status));
    }

    public List<OpenedPr> openedPullRequests() {
        return prs.values().stream().sorted(Comparator.comparing(p -> p.pr().number())).toList();
    }

    public List<String> comments() {
        return List.copyOf(comments);
    }

    @Override
    public void reset() {
        super.reset();
        listingFails = false;
        rateLimitedCalls.set(0);
        issues.clear();
        prs.clear();
        comments.clear();
    }

    // ---- GitHubClient ----

    /** The next {@code calls} PR openings fail with a rate limit lasting {@code retryAfter} (tests). */
    public void rateLimitNextPullRequests(int calls, java.time.Duration retryAfter) {
        rateLimitedCalls.set(calls);
        rateLimitWait = retryAfter;
    }

    /** Makes {@link #listOpenIssues} throw, like a GitHub outage (tests). */
    public void setListingFails(boolean fails) {
        listingFails = fails;
    }

    @Override
    public List<Issue> listOpenIssues(String repo, String label) {
        if (listingFails) {
            throw new StepFailedException("fake github: 503 listing issues (simulated)");
        }
        List<Issue> result = new ArrayList<>();
        for (Issue i : issues.values()) {
            if (i.repo().equals(repo) && i.labels().contains(label)) {
                result.add(i);
            }
        }
        result.sort(Comparator.comparingInt(Issue::number));
        return result;
    }

    @Override
    public PullRequest openPullRequest(PullRequestRequest req) {
        BranchPolicy.validatePullRequest(req.head(), req.base());
        if (rateLimitedCalls.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new com.ticketfactory.integration.RateLimitedException(
                    "fake github: secondary rate limit (simulated)", rateLimitWait);
        }
        for (OpenedPr existing : prs.values()) {
            if (existing.pr().head().equals(req.head()) && existing.repo().equals(req.repo())
                    && existing.status() != PrStatus.CLOSED) {
                return existing.pr();
            }
        }
        long ticketId = Long.parseLong(req.head().substring(BranchPolicy.PREFIX.length()));
        Issue issue = issues.get(key(req.repo(), req.issueNumber()));
        String issueBody = issue == null ? "" : issue.body();
        if (nextCallFails(new TicketContext(ticketId, req.repo(), req.issueNumber(), req.title(), issueBody, req.head()))) {
            throw new StepFailedException("fake github: 502 Bad Gateway opening PR (simulated)");
        }
        int number = prNumbers.incrementAndGet();
        PullRequest pr = new PullRequest(number, "https://github.com/" + req.repo() + "/pull/" + number,
                req.head(), req.base());
        String approval = FakeScript.parse(issueBody).text("approval").orElse(autoApprove ? "approved" : "pending");
        PrStatus initial = switch (approval) {
            case "approved" -> PrStatus.APPROVED;
            case "closed" -> PrStatus.CLOSED;
            default -> PrStatus.PENDING;
        };
        prs.put(number, new OpenedPr(pr, req.repo(), req.title(), req.body(), initial));
        return pr;
    }

    @Override
    public PrStatus getPullRequestStatus(String repo, int prNumber) {
        OpenedPr pr = prs.get(prNumber);
        return pr == null ? PrStatus.CLOSED : pr.status();
    }

    @Override
    public void commentOnIssue(String repo, int issueNumber, String body) {
        comments.add(repo + "#" + issueNumber + ": " + body);
    }
}
