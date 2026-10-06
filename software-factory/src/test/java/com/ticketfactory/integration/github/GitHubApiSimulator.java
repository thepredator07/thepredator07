package com.ticketfactory.integration.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, stateful stand-in for the parts of the GitHub REST API the factory uses, so {@link GitHubRestClient} can
 * be tested end to end over real HTTP: issues with labels and label events, pull requests, reviews, comments,
 * collaborator permissions, GitHub App token exchange, ETags, paging via {@code Link}, and injectable faults.
 * Behavior follows GitHub's documented responses; it is a test double, not a full emulation.
 */
public class GitHubApiSimulator implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        // The JDK's built-in server leaves Nagle's algorithm on, which adds ~40 ms to every small response.
        System.setProperty("sun.net.httpserver.nodelay", "true");
    }

    public record Request(String method, String pathAndQuery, String authorization, String ifNoneMatch) {
    }

    private record Fault(Pattern path, int status, Map<String, String> headers, String body) {
    }

    private static final class SimIssue {
        int number;
        String title;
        String body;
        String author;
        Set<String> labels = new LinkedHashSet<>();
        boolean open = true;
        boolean pullRequest;
        Instant createdAt;
        Instant updatedAt;
        List<ObjectNode> events = new ArrayList<>();
        List<String> comments = new ArrayList<>();
    }

    private static final class SimPr {
        int number;
        String head;
        String base;
        String title;
        String body;
        String state = "open";
        boolean merged;
        List<ObjectNode> reviews = new ArrayList<>();
    }

    private final HttpServer server;
    private final String repo;
    private final Map<Integer, SimIssue> issues = new LinkedHashMap<>();
    private final Map<Integer, SimPr> prs = new LinkedHashMap<>();
    private final Map<String, String> permissions = new HashMap<>();
    private final Set<String> validTokens = new LinkedHashSet<>();
    private final Deque<Fault> faults = new ArrayDeque<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger numbers = new AtomicInteger();
    private final AtomicInteger issuedAppTokens = new AtomicInteger();
    private Instant clock = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    /** Stale-read emulation: after a write, this many issue listings still return the state before it. */
    private int listingLag;
    private int staleListingsLeft;
    private ArrayNode staleListing;
    private PublicKey appPublicKey;
    private String appId;
    private long appTokenLifetimeSeconds = 3600;

    public GitHubApiSimulator(String repo, String token) throws IOException {
        this.repo = repo;
        this.validTokens.add(token);
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- fixture controls ----

    /** GitHub timestamps have second precision; every change advances the simulated clock by one second. */
    private synchronized Instant tick() {
        clock = clock.plusSeconds(1);
        return clock;
    }

    /**
     * Makes the issue listing lag like GitHub's does: after each write, the next {@code staleReads} listings return
     * the issues as they were before it.
     */
    public synchronized void setListingLag(int staleReads) {
        listingLag = staleReads;
    }

    private void beforeWrite() {
        if (listingLag > 0) {
            if (staleListingsLeft == 0) {
                staleListing = currentListing(null);
            }
            staleListingsLeft = listingLag;
        }
    }

    public synchronized int createIssue(String title, String body, String labeler, String... labels) {
        beforeWrite();
        SimIssue i = new SimIssue();
        i.number = numbers.incrementAndGet();
        i.title = title;
        i.body = body;
        i.author = "someone";
        i.createdAt = tick();
        i.updatedAt = i.createdAt;
        issues.put(i.number, i);
        for (String label : labels) {
            label(i.number, label, labeler);
        }
        return i.number;
    }

    /** Adds a pull request that also shows up in the issues API (as GitHub does). */
    public synchronized void createPullRequestIssue(String title, String label) {
        int n = createIssue(title, "", "maintainer", label);
        issues.get(n).pullRequest = true;
    }

    public synchronized void label(int number, String label, String actor) {
        beforeWrite();
        SimIssue i = issues.get(number);
        i.labels.add(label);
        i.updatedAt = tick();
        ObjectNode e = JSON.createObjectNode().put("event", "labeled").put("created_at", i.updatedAt.toString());
        e.putObject("label").put("name", label);
        e.putObject("actor").put("login", actor);
        i.events.add(e);
    }

    public synchronized void unlabel(int number, String label, String actor) {
        beforeWrite();
        SimIssue i = issues.get(number);
        i.labels.remove(label);
        i.updatedAt = tick();
        ObjectNode e = JSON.createObjectNode().put("event", "unlabeled").put("created_at", i.updatedAt.toString());
        e.putObject("label").put("name", label);
        e.putObject("actor").put("login", actor);
        i.events.add(e);
    }

    /** Adds unrelated timeline noise (comments, renames) to push the labeled event off the last events page. */
    public synchronized void addNoiseEvents(int number, int count) {
        SimIssue i = issues.get(number);
        for (int k = 0; k < count; k++) {
            i.events.add(JSON.createObjectNode().put("event", "renamed").put("created_at", tick().toString()));
        }
        i.updatedAt = clock;
    }

    public synchronized void closeIssue(int number) {
        beforeWrite();
        issues.get(number).open = false;
        issues.get(number).updatedAt = tick();
    }

    public synchronized void setPermission(String user, String permission) {
        permissions.put(user, permission);
    }

    public synchronized void review(int pr, String reviewer, String state) {
        ObjectNode r = JSON.createObjectNode().put("state", state).put("submitted_at", tick().toString());
        r.putObject("user").put("login", reviewer);
        prs.get(pr).reviews.add(r);
    }

    public synchronized void merge(int pr) {
        prs.get(pr).merged = true;
        prs.get(pr).state = "closed";
    }

    public synchronized void closePullRequest(int pr) {
        prs.get(pr).state = "closed";
    }

    /** The next request whose path (plus query) matches {@code pathRegex} gets this response instead. */
    public synchronized void failNext(String pathRegex, int status, Map<String, String> headers, String body) {
        faults.add(new Fault(Pattern.compile(pathRegex), status, headers, body));
    }

    public synchronized void enableAppAuth(String appId, PublicKey publicKey, long tokenLifetimeSeconds) {
        this.appId = appId;
        this.appPublicKey = publicKey;
        this.appTokenLifetimeSeconds = tokenLifetimeSeconds;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    public synchronized List<String> comments(int issue) {
        return List.copyOf(issues.get(issue).comments);
    }

    public synchronized int pullRequestCount() {
        return prs.size();
    }

    public int issuedAppTokens() {
        return issuedAppTokens.get();
    }

    // ---- HTTP ----

    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        String query = ex.getRequestURI().getRawQuery();
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String inm = ex.getRequestHeaders().getFirst("If-None-Match");
        requests.add(new Request(method, path + (query == null ? "" : "?" + query), auth, inm));
        byte[] in = ex.getRequestBody().readAllBytes();
        try {
            Fault fault = takeFault(path + (query == null ? "" : "?" + query));
            if (fault != null) {
                fault.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
                send(ex, fault.status(), fault.body(), null);
                return;
            }
            if (path.matches("/app/installations/[^/]+/access_tokens") && "POST".equals(method)) {
                exchangeAppToken(ex, auth);
                return;
            }
            if (auth == null || !validTokens.contains(auth.replaceFirst("^Bearer ", ""))) {
                send(ex, 401, "{\"message\":\"Bad credentials\"}", null);
                return;
            }
            route(ex, method, path, parseQuery(query), in);
        } catch (RuntimeException e) {
            send(ex, 500, "{\"message\":\"simulator error: " + e.getMessage() + "\"}", null);
        }
    }

    private synchronized Fault takeFault(String path) {
        for (Fault f : faults) {
            if (f.path().matcher(path).find()) {
                faults.remove(f);
                return f;
            }
        }
        return null;
    }

    private void route(HttpExchange ex, String method, String path, Map<String, String> q, byte[] in)
            throws IOException {
        String base = "/repos/" + repo;
        Matcher m;
        if ("GET".equals(method) && path.equals(base + "/issues")) {
            listIssues(ex, q);
        } else if ("GET".equals(method) && (m = match(base + "/issues/(\\d+)/events", path)) != null) {
            listEvents(ex, Integer.parseInt(m.group(1)), q);
        } else if ("POST".equals(method) && (m = match(base + "/issues/(\\d+)/comments", path)) != null) {
            synchronized (this) {
                issues.get(Integer.parseInt(m.group(1))).comments.add(JSON.readTree(in).path("body").asText());
            }
            send(ex, 201, "{\"id\":1}", null);
        } else if ("GET".equals(method) && (m = match(base + "/collaborators/([^/]+)/permission", path)) != null) {
            String user = URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
            String p;
            synchronized (this) {
                p = permissions.get(user);
            }
            if (p == null) {
                send(ex, 404, "{\"message\":\"Not Found\"}", null);
            } else {
                send(ex, 200, JSON.createObjectNode().put("permission", p).toString(), null);
            }
        } else if ("GET".equals(method) && path.equals(base + "/pulls")) {
            listPulls(ex, q);
        } else if ("POST".equals(method) && path.equals(base + "/pulls")) {
            createPull(ex, JSON.readTree(in));
        } else if ("GET".equals(method) && (m = match(base + "/pulls/(\\d+)", path)) != null) {
            SimPr pr;
            synchronized (this) {
                pr = prs.get(Integer.parseInt(m.group(1)));
            }
            if (pr == null) {
                send(ex, 404, "{\"message\":\"Not Found\"}", null);
            } else {
                sendJson(ex, 200, prJson(pr), ex.getRequestHeaders().getFirst("If-None-Match"));
            }
        } else if ("GET".equals(method) && (m = match(base + "/pulls/(\\d+)/reviews", path)) != null) {
            ArrayNode arr = JSON.createArrayNode();
            synchronized (this) {
                prs.get(Integer.parseInt(m.group(1))).reviews.forEach(arr::add);
            }
            sendPage(ex, arr, q);
        } else {
            send(ex, 404, "{\"message\":\"Not Found: " + method + " " + path + "\"}", null);
        }
    }

    private void listIssues(HttpExchange ex, Map<String, String> q) throws IOException {
        String label = q.get("labels");
        ArrayNode arr;
        synchronized (this) {
            if (staleListingsLeft > 0) {
                staleListingsLeft--;
                arr = JSON.createArrayNode();
                for (JsonNode i : staleListing) {
                    boolean hasLabel = false;
                    for (JsonNode l : i.path("labels")) {
                        hasLabel |= l.path("name").asText().equals(label);
                    }
                    if (label == null || hasLabel) {
                        arr.add(i);
                    }
                }
            } else {
                arr = currentListing(label);
            }
        }
        sendPage(ex, arr, q);
    }

    /** All open issues (optionally with {@code label}), as the issues API returns them. */
    private ArrayNode currentListing(String label) {
        ArrayNode arr = JSON.createArrayNode();
        for (SimIssue i : issues.values()) {
            if (i.open && (label == null || i.labels.contains(label))) {
                ObjectNode o = arr.addObject().put("number", i.number).put("title", i.title)
                        .put("created_at", i.createdAt.toString()).put("updated_at", i.updatedAt.toString());
                if (i.body == null) {
                    o.putNull("body");
                } else {
                    o.put("body", i.body);
                }
                o.putObject("user").put("login", i.author);
                ArrayNode labels = o.putArray("labels");
                i.labels.forEach(l -> labels.addObject().put("name", l));
                if (i.pullRequest) {
                    o.putObject("pull_request").put("url", "x");
                }
            }
        }
        return arr;
    }

    private void listEvents(HttpExchange ex, int number, Map<String, String> q) throws IOException {
        ArrayNode arr = JSON.createArrayNode();
        synchronized (this) {
            issues.get(number).events.forEach(arr::add);
        }
        sendPage(ex, arr, q);
    }

    private void listPulls(HttpExchange ex, Map<String, String> q) throws IOException {
        String head = q.get("head"); // owner:branch
        String branch = head == null ? null : head.substring(head.indexOf(':') + 1);
        ArrayNode arr = JSON.createArrayNode();
        synchronized (this) {
            for (SimPr pr : prs.values()) {
                if ("open".equals(pr.state) && (branch == null || branch.equals(pr.head))) {
                    arr.add(prJson(pr));
                }
            }
        }
        sendJson(ex, 200, arr, ex.getRequestHeaders().getFirst("If-None-Match"));
    }

    private void createPull(HttpExchange ex, JsonNode body) throws IOException {
        SimPr pr;
        synchronized (this) {
            String head = body.path("head").asText();
            for (SimPr existing : prs.values()) {
                if ("open".equals(existing.state) && existing.head.equals(head)) {
                    send(ex, 422, "{\"message\":\"Validation Failed\",\"errors\":[{\"message\":"
                            + "\"A pull request already exists for owner:" + head + ".\"}]}", null);
                    return;
                }
            }
            pr = new SimPr();
            pr.number = numbers.incrementAndGet();
            pr.head = head;
            pr.base = body.path("base").asText();
            pr.title = body.path("title").asText();
            pr.body = body.path("body").asText();
            prs.put(pr.number, pr);
        }
        sendJson(ex, 201, prJson(pr), null);
    }

    private ObjectNode prJson(SimPr pr) {
        ObjectNode o = JSON.createObjectNode().put("number", pr.number)
                .put("html_url", "https://github.com/" + repo + "/pull/" + pr.number)
                .put("state", pr.state).put("merged", pr.merged).put("title", pr.title);
        o.putObject("head").put("ref", pr.head);
        o.putObject("base").put("ref", pr.base);
        return o;
    }

    private void exchangeAppToken(HttpExchange ex, String auth) throws IOException {
        if (appPublicKey == null || auth == null || !verifyJwt(auth.replaceFirst("^Bearer ", ""))) {
            send(ex, 401, "{\"message\":\"A JSON web token could not be decoded\"}", null);
            return;
        }
        String token = "ghs_sim" + issuedAppTokens.incrementAndGet();
        synchronized (this) {
            validTokens.add(token);
        }
        Instant expires = Instant.now().plusSeconds(appTokenLifetimeSeconds);
        send(ex, 201, JSON.createObjectNode().put("token", token).put("expires_at", expires.toString()).toString(),
                null);
    }

    private boolean verifyJwt(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(appPublicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(Base64.getUrlDecoder().decode(parts[2]))) {
                return false;
            }
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            long now = Instant.now().getEpochSecond();
            // GitHub tolerates some clock drift; 120 s here.
            return appId.equals(claims.path("iss").asText()) && claims.path("iat").asLong() <= now + 120
                    && claims.path("exp").asLong() > now && claims.path("exp").asLong() - claims.path("iat").asLong() <= 600;
        } catch (Exception e) {
            return false;
        }
    }

    /** Pages a JSON array with GitHub's {@code per_page} / {@code page} params and {@code Link} header. */
    private void sendPage(HttpExchange ex, ArrayNode all, Map<String, String> q) throws IOException {
        int perPage = Integer.parseInt(q.getOrDefault("per_page", "30"));
        int page = Integer.parseInt(q.getOrDefault("page", "1"));
        int pages = Math.max(1, (all.size() + perPage - 1) / perPage);
        ArrayNode slice = JSON.createArrayNode();
        for (int k = (page - 1) * perPage; k < Math.min(all.size(), page * perPage); k++) {
            slice.add(all.get(k));
        }
        List<String> links = new ArrayList<>();
        if (page < pages) {
            links.add("<" + pageUrl(ex, q, page + 1) + ">; rel=\"next\"");
            links.add("<" + pageUrl(ex, q, pages) + ">; rel=\"last\"");
        }
        if (!links.isEmpty()) {
            ex.getResponseHeaders().add("Link", String.join(", ", links));
        }
        sendJson(ex, 200, slice, ex.getRequestHeaders().getFirst("If-None-Match"));
    }

    private String pageUrl(HttpExchange ex, Map<String, String> q, int page) {
        Map<String, String> params = new LinkedHashMap<>(q);
        params.put("page", Integer.toString(page));
        StringBuilder sb = new StringBuilder(url() + ex.getRequestURI().getPath() + "?");
        params.forEach((k, v) -> sb.append(k).append('=')
                .append(java.net.URLEncoder.encode(v, StandardCharsets.UTF_8)).append('&'));
        return sb.substring(0, sb.length() - 1);
    }

    /** Sends JSON with an ETag; answers 304 when the client already has this exact body. */
    private void sendJson(HttpExchange ex, int status, JsonNode body, String ifNoneMatch) throws IOException {
        String text = body.toString();
        String etag = "\"" + sha(text + ex.getResponseHeaders().getFirst("Link")) + "\"";
        ex.getResponseHeaders().add("ETag", etag);
        if (status == 200 && etag.equals(ifNoneMatch)) {
            ex.sendResponseHeaders(304, -1);
            ex.close();
            return;
        }
        send(ex, status, text, null);
    }

    private static void send(HttpExchange ex, int status, String body, String unused) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("x-ratelimit-remaining", "4999");
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(bytes);
            }
        }
        ex.close();
    }

    private static Matcher match(String regex, String path) {
        Matcher m = Pattern.compile(regex).matcher(path);
        return m.matches() ? m : null;
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> q = new LinkedHashMap<>();
        if (raw != null) {
            for (String kv : raw.split("&")) {
                int i = kv.indexOf('=');
                if (i > 0) {
                    q.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8),
                            URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
                }
            }
        }
        return q;
    }

    private static String sha(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
