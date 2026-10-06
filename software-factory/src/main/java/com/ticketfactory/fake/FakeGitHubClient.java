package com.ticketfactory.fake;

import com.ticketfactory.integration.BranchPolicy;
import com.ticketfactory.integration.GitHubClient;
import com.ticketfactory.integration.StepFailedException;
import com.ticketfactory.integration.TicketContext;
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

    public void addIssue(String repo, int number, String title, String body, String... labels) {
        issues.put(repo + "#" + number, new Issue(repo, number, title, body, Set.of(labels)));
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
        issues.clear();
        prs.clear();
        comments.clear();
    }

    // ---- GitHubClient ----

    @Override
    public List<Issue> listOpenIssues(String repo, String label) {
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
        long ticketId = Long.parseLong(req.head().substring(BranchPolicy.PREFIX.length()));
        Issue issue = issues.get(req.repo() + "#" + req.issueNumber());
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
