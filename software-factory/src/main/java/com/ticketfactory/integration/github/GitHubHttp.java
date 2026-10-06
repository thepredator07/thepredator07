package com.ticketfactory.integration.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketfactory.integration.RateLimitedException;
import com.ticketfactory.integration.StepFailedException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thin JSON-over-HTTP layer for the GitHub REST API.
 * <ul>
 *   <li>GETs are cached by ETag: repeat requests send {@code If-None-Match}, and a {@code 304} (which GitHub does not
 *       count against the rate limit) returns the cached body.</li>
 *   <li>{@link #getAllPages} follows {@code Link: rel="next"} until the end, so listings are complete.</li>
 *   <li>Rate limits become {@link RateLimitedException} with the time to wait; 5xx and network errors become
 *       {@link StepFailedException}; other 4xx become {@link GitHubApiException}.</li>
 * </ul>
 */
public class GitHubHttp {

    static final String API_VERSION = "2022-11-28";
    private static final Logger log = LoggerFactory.getLogger(GitHubHttp.class);
    private static final Pattern NEXT = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    private static final Pattern LAST = Pattern.compile("<([^>]+)>;\\s*rel=\"last\"");
    private static final int ETAG_CACHE_SIZE = 1000;

    private final String apiUrl;
    private final GitHubAuth auth;
    private final HttpClient http;
    private final Duration timeout;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, Cached> etags = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > ETAG_CACHE_SIZE;
                }
            });

    private record Cached(String etag, String body, String link) {
    }

    /** One response: parsed body plus the {@code Link} header (for paging). */
    public record Response(int status, JsonNode body, String link) {
    }

    public GitHubHttp(String apiUrl, GitHubAuth auth, HttpClient http, Duration timeout, Clock clock) {
        this.apiUrl = apiUrl.replaceAll("/+$", "");
        this.auth = auth;
        this.http = http;
        this.timeout = timeout;
        this.clock = clock;
    }

    public JsonNode get(String pathOrUrl) {
        return send("GET", pathOrUrl, null).body();
    }

    public JsonNode post(String path, Object body) {
        return send("POST", path, body).body();
    }

    /** Every item of a paged list endpoint, in order. */
    public List<JsonNode> getAllPages(String path) {
        List<JsonNode> items = new ArrayList<>();
        String next = path;
        int pages = 0;
        while (next != null) {
            Response page = send("GET", next, null);
            page.body().forEach(items::add);
            next = linkTo(page.link(), NEXT).orElse(null);
            if (++pages > 1000) {
                throw new StepFailedException("GitHub paging did not end after 1000 pages: " + path);
            }
        }
        return items;
    }

    /** The last page of a paged list (newest items, for endpoints sorted oldest first). */
    public List<JsonNode> getLastPage(String path) {
        Response first = send("GET", path, null);
        Optional<String> last = linkTo(first.link(), LAST);
        JsonNode body = last.isPresent() ? send("GET", last.get(), null).body() : first.body();
        List<JsonNode> items = new ArrayList<>();
        body.forEach(items::add);
        return items;
    }

    private Response send(String method, String pathOrUrl, Object body) {
        String url = pathOrUrl.startsWith("http") ? pathOrUrl : apiUrl + pathOrUrl;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Authorization", auth.authorizationHeader())
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", "ticket-to-pr-software-factory");
        Cached cached = null;
        if ("GET".equals(method)) {
            cached = etags.get(url);
            if (cached != null) {
                builder.header("If-None-Match", cached.etag());
            }
            builder.GET();
        } else {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(toJson(body)));
        }
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new StepFailedException("GitHub " + method + " " + pathOrUrl + " failed: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepFailedException("interrupted calling GitHub", e);
        }
        warnIfRateLimitLow(response);
        int status = response.statusCode();
        if (status == 304 && cached != null) {
            return new Response(200, parse(cached.body()), cached.link());
        }
        if (status >= 200 && status < 300) {
            String link = response.headers().firstValue("Link").orElse(null);
            response.headers().firstValue("ETag").filter(e -> "GET".equals(method))
                    .ifPresent(etag -> etags.put(url, new Cached(etag, response.body(), link)));
            return new Response(status, parse(response.body()), link);
        }
        throw toException(method, pathOrUrl, response);
    }

    private RuntimeException toException(String method, String path, HttpResponse<String> response) {
        int status = response.statusCode();
        JsonNode error = parse(response.body());
        // GitHub puts the useful part of a 422 in errors[].message ("A pull request already exists for ...").
        StringBuilder message = new StringBuilder(error.path("message").asText(response.body()));
        error.path("errors").forEach(e -> {
            String detail = e.isTextual() ? e.asText() : e.path("message").asText("");
            if (!detail.isBlank()) {
                message.append(": ").append(detail);
            }
        });
        if (status == 403 || status == 429) {
            Optional<Duration> wait = rateLimitWait(response);
            if (wait.isPresent()) {
                return new RateLimitedException("GitHub rate limit on " + method + " " + path + ": " + message,
                        wait.get());
            }
        }
        if (status >= 500) {
            return new StepFailedException("GitHub " + method + " " + path + " returned " + status + ": " + message);
        }
        return new GitHubApiException(status, method + " " + path + ": " + message);
    }

    /** Primary limit: {@code x-ratelimit-remaining: 0} until {@code x-ratelimit-reset}. Secondary: {@code Retry-After}. */
    private Optional<Duration> rateLimitWait(HttpResponse<String> response) {
        Optional<String> retryAfter = response.headers().firstValue("Retry-After");
        if (retryAfter.isPresent()) {
            return Optional.of(Duration.ofSeconds(Math.max(1, Long.parseLong(retryAfter.get().trim()))));
        }
        boolean exhausted = response.headers().firstValue("x-ratelimit-remaining").map("0"::equals).orElse(false);
        if (exhausted) {
            long reset = response.headers().firstValue("x-ratelimit-reset").map(Long::parseLong)
                    .orElse(clock.instant().getEpochSecond() + 60);
            long seconds = Math.max(1, reset - clock.instant().getEpochSecond());
            return Optional.of(Duration.ofSeconds(seconds));
        }
        return Optional.empty();
    }

    private void warnIfRateLimitLow(HttpResponse<String> response) {
        response.headers().firstValue("x-ratelimit-remaining").map(Long::parseLong).filter(r -> r > 0 && r < 100)
                .ifPresent(r -> log.warn("GitHub rate limit low: {} requests left until {}", r,
                        response.headers().firstValue("x-ratelimit-reset")
                                .map(s -> Instant.ofEpochSecond(Long.parseLong(s)).toString()).orElse("?")));
    }

    private static Optional<String> linkTo(String linkHeader, Pattern rel) {
        if (linkHeader == null) {
            return Optional.empty();
        }
        Matcher m = rel.matcher(linkHeader);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(body);
        } catch (IOException e) {
            return json.createObjectNode().put("message", body);
        }
    }

    private String toJson(Object body) {
        try {
            return body == null ? "{}" : json.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
