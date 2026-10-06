package com.ticketfactory.integration.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticketfactory.integration.BranchPolicy;
import com.ticketfactory.integration.GitHubClient;
import com.ticketfactory.integration.github.GitHubProperties.Permission;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GitHubClient} over the GitHub REST API.
 *
 * <p>Who may trigger the factory: an issue counts only if the person who last applied the trigger label has at least
 * {@code factory.github.min-labeler-permission} on the repo (default write). Anyone can open an issue, so this is what
 * stops strangers from pointing the agent at arbitrary text.
 *
 * <p>API cost per poll: one paged issue listing (free when unchanged, thanks to ETags). Label events and labeler
 * permissions are fetched only for issues that changed since the last poll, and permissions are cached.
 */
public class GitHubRestClient implements GitHubClient {

    private static final Logger log = LoggerFactory.getLogger(GitHubRestClient.class);

    private final GitHubHttp http;
    private final GitHubProperties props;
    private final Clock clock;

    /** Trigger info per issue, valid while the issue's {@code updated_at} is unchanged (labeling updates it). */
    private record TriggerInfo(String updatedAt, Instant triggeredAt, String labeler) {
    }

    private record CachedPermission(Permission permission, Instant fetchedAt) {
    }

    private final Map<String, TriggerInfo> triggers = new ConcurrentHashMap<>();
    private final Map<String, CachedPermission> permissions = new ConcurrentHashMap<>();
    private final Set<String> warnedIgnored = ConcurrentHashMap.newKeySet();

    public GitHubRestClient(GitHubHttp http, GitHubProperties props, Clock clock) {
        this.http = http;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public List<Issue> listOpenIssues(String repo, String label) {
        List<JsonNode> items = http.getAllPages("/repos/" + repo + "/issues?state=open&labels=" + enc(label)
                + "&per_page=" + props.pageSize());
        List<Issue> issues = new ArrayList<>();
        for (JsonNode item : items) {
            if (item.has("pull_request")) {
                continue; // the issues API also returns pull requests
            }
            int number = item.path("number").asInt();
            TriggerInfo trigger = triggerInfo(repo, number, item, label);
            if (!mayTrigger(repo, number, trigger)) {
                continue;
            }
            Set<String> labels = new LinkedHashSet<>();
            item.path("labels").forEach(l -> labels.add(l.path("name").asText()));
            issues.add(new Issue(repo, number, item.path("title").asText(), textOrEmpty(item.path("body")),
                    Set.copyOf(labels), trigger.triggeredAt()));
        }
        return issues;
    }

    @Override
    public PullRequest openPullRequest(PullRequestRequest req) {
        BranchPolicy.validatePullRequest(req.head(), req.base());
        Optional<PullRequest> existing = findOpenPullRequest(req.repo(), req.head());
        if (existing.isPresent()) {
            return existing.get();
        }
        Map<String, Object> body = new HashMap<>();
        body.put("title", req.title());
        body.put("head", req.head());
        body.put("base", req.base());
        body.put("body", req.body());
        try {
            return toPullRequest(http.post("/repos/" + req.repo() + "/pulls", body));
        } catch (GitHubApiException e) {
            // Another worker (or a crashed run) created it between our lookup and our create.
            if (e.status() == 422 && e.getMessage().contains("already exists")) {
                return findOpenPullRequest(req.repo(), req.head()).orElseThrow(() -> e);
            }
            throw e;
        }
    }

    @Override
    public PrStatus getPullRequestStatus(String repo, int prNumber) {
        JsonNode pr;
        try {
            pr = http.get("/repos/" + repo + "/pulls/" + prNumber);
        } catch (GitHubApiException e) {
            if (e.status() == 404) {
                return PrStatus.CLOSED;
            }
            throw e;
        }
        if (pr.path("merged").asBoolean(false)) {
            return PrStatus.APPROVED;
        }
        if ("closed".equals(pr.path("state").asText())) {
            return PrStatus.CLOSED;
        }
        // The latest decisive review per reviewer counts. Any outstanding "changes requested" blocks approval.
        Map<String, String> latest = new HashMap<>();
        for (JsonNode review : http.getAllPages("/repos/" + repo + "/pulls/" + prNumber + "/reviews?per_page=100")) {
            String state = review.path("state").asText();
            String user = review.path("user").path("login").asText();
            switch (state) {
                case "APPROVED", "CHANGES_REQUESTED" -> latest.put(user, state);
                case "DISMISSED" -> latest.remove(user);
                default -> { } // COMMENTED, PENDING: not a decision
            }
        }
        if (latest.containsValue("CHANGES_REQUESTED")) {
            return PrStatus.PENDING; // TODO(phase-2, M7): send review comments back to CODING (finding 13)
        }
        return latest.containsValue("APPROVED") ? PrStatus.APPROVED : PrStatus.PENDING;
    }

    @Override
    public void commentOnIssue(String repo, int issueNumber, String body) {
        http.post("/repos/" + repo + "/issues/" + issueNumber + "/comments", Map.of("body", body));
    }

    // ---- helpers ----

    private Optional<PullRequest> findOpenPullRequest(String repo, String head) {
        String owner = repo.substring(0, repo.indexOf('/'));
        JsonNode list = http.get("/repos/" + repo + "/pulls?state=open&head=" + enc(owner + ":" + head));
        for (JsonNode pr : list) {
            if (head.equals(pr.path("head").path("ref").asText())) {
                return Optional.of(toPullRequest(pr));
            }
        }
        return Optional.empty();
    }

    private TriggerInfo triggerInfo(String repo, int number, JsonNode issue, String label) {
        String key = repo + "#" + number;
        String updatedAt = issue.path("updated_at").asText();
        TriggerInfo cached = triggers.get(key);
        if (cached != null && cached.updatedAt().equals(updatedAt)) {
            return cached;
        }
        String eventsPath = "/repos/" + repo + "/issues/" + number + "/events?per_page=100";
        Optional<TriggerInfo> found = latestLabeling(http.getLastPage(eventsPath), label, updatedAt);
        if (found.isEmpty()) {
            found = latestLabeling(http.getAllPages(eventsPath), label, updatedAt);
        }
        // No labeled event visible (should not happen): fall back to the issue's creation by its author.
        TriggerInfo info = found.orElseGet(() -> new TriggerInfo(updatedAt,
                Instant.parse(issue.path("created_at").asText()), issue.path("user").path("login").asText()));
        triggers.put(key, info);
        return info;
    }

    private static Optional<TriggerInfo> latestLabeling(List<JsonNode> events, String label, String updatedAt) {
        TriggerInfo latest = null;
        for (JsonNode e : events) {
            if ("labeled".equals(e.path("event").asText()) && label.equals(e.path("label").path("name").asText())) {
                latest = new TriggerInfo(updatedAt, Instant.parse(e.path("created_at").asText()),
                        e.path("actor").path("login").asText());
            }
        }
        return Optional.ofNullable(latest);
    }

    private boolean mayTrigger(String repo, int number, TriggerInfo trigger) {
        Permission permission = permission(repo, trigger.labeler());
        if (permission.atLeast(props.minLabelerPermission())) {
            return true;
        }
        if (warnedIgnored.add(repo + "#" + number + "@" + trigger.triggeredAt())) {
            log.warn("Ignoring {}#{}: labeled by '{}' who has {} permission (needs {})", repo, number,
                    trigger.labeler(), permission, props.minLabelerPermission());
        }
        return false;
    }

    private Permission permission(String repo, String user) {
        String key = repo + ":" + user;
        CachedPermission cached = permissions.get(key);
        if (cached != null && cached.fetchedAt().plus(props.permissionCacheTtl()).isAfter(clock.instant())) {
            return cached.permission();
        }
        Permission p;
        try {
            p = Permission.fromApi(http.get("/repos/" + repo + "/collaborators/" + enc(user) + "/permission")
                    .path("permission").asText());
        } catch (GitHubApiException e) {
            if (e.status() != 404) {
                throw e;
            }
            p = Permission.NONE; // not a collaborator
        }
        permissions.put(key, new CachedPermission(p, clock.instant()));
        return p;
    }

    private static PullRequest toPullRequest(JsonNode pr) {
        return new PullRequest(pr.path("number").asInt(), pr.path("html_url").asText(),
                pr.path("head").path("ref").asText(), pr.path("base").path("ref").asText());
    }

    private static String textOrEmpty(JsonNode node) {
        return node.isNull() || node.isMissingNode() ? "" : node.asText();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
