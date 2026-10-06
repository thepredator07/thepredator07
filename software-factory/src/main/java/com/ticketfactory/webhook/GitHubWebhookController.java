package com.ticketfactory.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.intake.GitHubPoller;
import com.ticketfactory.queue.JobQueue;
import com.ticketfactory.ticket.TicketRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Receives GitHub webhooks so the factory reacts within seconds. Polling stays on as the fallback for missed
 * deliveries, so a webhook only ever <i>speeds things up</i>: it never changes state by itself.
 *
 * <ul>
 *   <li>Every delivery must carry a valid {@code X-Hub-Signature-256} (HMAC-SHA256 of the body with the shared
 *       secret), compared in constant time. Without a configured secret the endpoint answers 404.</li>
 *   <li>{@code issues} events (labeled, unlabeled, closed, reopened, opened, edited) start a poll, which applies the
 *       usual rules (labeler permission, two misses before cancelling). Polls are coalesced: a burst of events causes
 *       at most one poll running and one queued.</li>
 *   <li>{@code pull_request_review} and closed {@code pull_request} events wake the job of the ticket waiting on that
 *       PR, which then reads the PR's status from the API as usual.</li>
 *   <li>Events for other repositories are ignored.</li>
 * </ul>
 */
@RestController
public class GitHubWebhookController implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GitHubWebhookController.class);
    private static final Set<String> ISSUE_ACTIONS = Set.of("labeled", "unlabeled", "closed", "reopened", "opened",
            "edited", "deleted", "transferred");
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private final WebhookProperties props;
    private final FactoryProperties factory;
    private final GitHubPoller poller;
    private final TicketRepository tickets;
    private final JobQueue queue;
    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService pollRunner = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
    private final AtomicBoolean pollQueued = new AtomicBoolean();

    public GitHubWebhookController(WebhookProperties props, FactoryProperties factory, GitHubPoller poller,
                                   TicketRepository tickets, JobQueue queue) {
        this.props = props;
        this.factory = factory;
        this.poller = poller;
        this.tickets = tickets;
        this.queue = queue;
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<String> receive(@RequestHeader(value = "X-GitHub-Event", required = false) String event,
                                          @RequestHeader(value = "X-Hub-Signature-256", required = false)
                                          String signature,
                                          @RequestBody(required = false) byte[] body) {
        if (!props.enabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (body == null || body.length > MAX_BODY_BYTES) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("bad body");
        }
        if (!validSignature(body, signature)) {
            log.warn("Rejected webhook delivery with a missing or wrong signature (event {})", event);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("bad signature");
        }
        JsonNode payload;
        try {
            payload = json.readTree(body);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("not JSON");
        }
        if ("ping".equals(event)) {
            return ResponseEntity.ok("pong");
        }
        String repo = payload.path("repository").path("full_name").asText("");
        if (!repo.equalsIgnoreCase(factory.repo())) {
            return ResponseEntity.accepted().body("ignored: other repository");
        }
        String action = payload.path("action").asText("");
        return switch (event == null ? "" : event) {
            case "issues" -> {
                if (ISSUE_ACTIONS.contains(action)) {
                    pollSoon();
                    yield ResponseEntity.accepted().body("poll scheduled");
                }
                yield ResponseEntity.accepted().body("ignored: issues/" + action);
            }
            case "pull_request_review" -> wake(payload.path("pull_request").path("number").asInt(0));
            case "pull_request" -> "closed".equals(action)
                    ? wake(payload.path("pull_request").path("number").asInt(0))
                    : ResponseEntity.accepted().body("ignored: pull_request/" + action);
            default -> ResponseEntity.accepted().body("ignored: " + event);
        };
    }

    private ResponseEntity<String> wake(int prNumber) {
        return tickets.findAwaitingApproval(factory.repo(), prNumber)
                .map(t -> {
                    queue.wakeUp(t.id());
                    log.info("Webhook: PR #{} changed, checking ticket {} now", prNumber, t.id());
                    return ResponseEntity.accepted().body("ticket " + t.id() + " woken");
                })
                .orElseGet(() -> ResponseEntity.accepted().body("ignored: no ticket waits on PR #" + prNumber));
    }

    /** At most one poll running and one waiting, however many events arrive. */
    void pollSoon() {
        if (!pollQueued.compareAndSet(false, true)) {
            return;
        }
        pollRunner.submit(() -> {
            pollQueued.set(false);
            try {
                poller.poll();
            } catch (RuntimeException e) {
                log.warn("Webhook-triggered poll failed (the scheduled poll will retry): {}", e.toString());
            }
        });
    }

    boolean validSignature(byte[] body, String header) {
        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(header.substring("sha256=".length()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        return MessageDigest.isEqual(hmac(props.secret(), body), given);
    }

    static byte[] hmac(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void destroy() {
        pollRunner.shutdownNow();
    }
}
