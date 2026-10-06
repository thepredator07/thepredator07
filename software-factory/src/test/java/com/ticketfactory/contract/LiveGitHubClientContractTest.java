package com.ticketfactory.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketfactory.integration.GitHubClient;
import com.ticketfactory.integration.github.GitHubProperties;
import com.ticketfactory.integration.real.RealIntegrationsConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;

/**
 * The GitHub contract against a real, dedicated test repository. Tagged {@code live}: excluded from normal builds,
 * run by {@code .github/workflows/factory-live.yml} (nightly or on demand) when these are set:
 * <ul>
 *   <li>{@code FACTORY_LIVE_REPO}: owner/name of a throwaway repo (never a real project: this test opens and closes
 *       issues and PRs, and merges tiny files into its default branch to test "approved")</li>
 *   <li>{@code FACTORY_LIVE_TOKEN}: a token with issues, pull requests and contents write access to that repo</li>
 * </ul>
 * Everything it creates is closed or deleted afterwards.
 */
@Tag("live")
class LiveGitHubClientContractTest extends GitHubClientContract {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API = System.getenv().getOrDefault("GITHUB_API_URL", "https://api.github.com");

    private final String repo = required("FACTORY_LIVE_REPO");
    private final String token = required("FACTORY_LIVE_TOKEN");
    private final HttpClient http = HttpClient.newHttpClient();
    private final List<Integer> createdIssues = new ArrayList<>();
    private final List<String> createdBranches = new ArrayList<>();
    private GitHubClient client;

    @BeforeEach
    void connect() {
        // Small pages so a handful of issues still exercises paging.
        client = RealIntegrationsConfig.createGitHubClient(new GitHubProperties(API, token, null, null, null,
                GitHubProperties.Permission.WRITE, 2, Duration.ofSeconds(20), Duration.ofMinutes(10)),
                Clock.systemUTC());
    }

    @AfterEach
    void cleanUp() {
        for (JsonNode pr : call("GET", "/repos/" + repo + "/pulls?state=open&per_page=100", null)) {
            if (createdBranches.contains(pr.path("head").path("ref").asText())) {
                call("PATCH", "/repos/" + repo + "/pulls/" + pr.path("number").asInt(), Map.of("state", "closed"));
            }
        }
        createdBranches.forEach(b -> call("DELETE", "/repos/" + repo + "/git/refs/heads/" + b, null));
        createdIssues.forEach(n -> call("PATCH", "/repos/" + repo + "/issues/" + n, Map.of("state", "closed")));
    }

    @Override
    protected GitHubClient client() {
        return client;
    }

    @Override
    protected String repo() {
        return repo;
    }

    /** GitHub's labelled-issue listing lags behind writes by a few seconds; allow generously. */
    @Override
    protected Duration listingConsistencyWait() {
        return Duration.ofSeconds(90);
    }

    @Override
    protected int manyIssues() {
        return 5; // with page size 2: three pages
    }

    @Override
    protected int openIssue(String title, String body, String... labels) {
        int n = call("POST", "/repos/" + repo + "/issues",
                Map.of("title", expectedTitle(title), "body", body, "labels", List.of(labels)))
                .path("number").asInt();
        createdIssues.add(n);
        return n;
    }

    /** Issues made by this test are marked, so they are easy to spot (and ignore) in the test repo. */
    @Override
    protected String expectedTitle(String title) {
        return "[contract test] " + title;
    }

    @Override
    protected void prepareBranch(String head) {
        JsonNode repoInfo = call("GET", "/repos/" + repo, null);
        String base = repoInfo.path("default_branch").asText();
        String sha = call("GET", "/repos/" + repo + "/git/ref/heads/" + base, null).path("object").path("sha").asText();
        call("POST", "/repos/" + repo + "/git/refs", Map.of("ref", "refs/heads/" + head, "sha", sha));
        createdBranches.add(head);
        String file = "contract-test/" + head.replace('/', '-') + ".txt";
        call("PUT", "/repos/" + repo + "/contents/" + file, Map.of("message", "contract test", "branch", head,
                "content", Base64.getEncoder().encodeToString(head.getBytes(StandardCharsets.UTF_8))));
    }

    /** A token can't approve its own PR, so "approved" is tested via merge (which the client also reports as APPROVED). */
    @Override
    protected void approve(int prNumber) {
        call("PUT", "/repos/" + repo + "/pulls/" + prNumber + "/merge", Map.of("merge_method", "squash"));
    }

    private JsonNode call(String method, String path, Object body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(API + path))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json");
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300 && !(method.equals("DELETE") && r.statusCode() == 422)) {
                throw new IllegalStateException(method + " " + path + " -> " + r.statusCode() + " " + r.body());
            }
            return r.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.body());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String required(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(name + " must be set to run live tests");
        }
        return v;
    }
}
