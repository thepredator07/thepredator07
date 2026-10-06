package com.ticketfactory.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.integration.GitHubClient.PrStatus;
import com.ticketfactory.intake.GitHubPoller;
import com.ticketfactory.queue.WorkerPool;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.TicketState;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** GitHub webhooks: signature checked, events only speed up what polling would do anyway. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "factory.webhook.secret=s3cret-for-tests")
class GitHubWebhookTest extends AbstractIntegrationTest {

    static final String SECRET = "s3cret-for-tests";

    @Autowired MockMvc mvc;
    @Autowired FakeGitHubClient github;
    @Autowired GitHubPoller poller;
    @Autowired WorkerPool workers;
    @Autowired TicketRepository tickets;
    @Autowired FactoryProperties props;

    @BeforeEach
    void reset() {
        github.reset();
    }

    private ResultActions deliver(String event, String body, String signature) throws Exception {
        var request = post("/webhooks/github").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-GitHub-Event", event);
        if (signature != null) {
            request.header("X-Hub-Signature-256", signature);
        }
        return mvc.perform(request); // no CSRF token and no sign-in: GitHub has neither
    }

    private ResultActions deliver(String event, String body) throws Exception {
        return deliver(event, body, sign(SECRET, body));
    }

    static String sign(String secret, String body) {
        return "sha256=" + HexFormat.of().formatHex(
                GitHubWebhookController.hmac(secret, body.getBytes(StandardCharsets.UTF_8)));
    }

    private String issueEvent(String action, String repo) {
        return "{\"action\":\"" + action + "\",\"repository\":{\"full_name\":\"" + repo + "\"},\"issue\":{\"number\":1}}";
    }

    @Test
    void deliveriesWithoutAValidSignatureAreRejected() throws Exception {
        String body = issueEvent("labeled", props.repo());
        deliver("issues", body, null).andExpect(status().isUnauthorized());
        deliver("issues", body, sign("wrong-secret", body)).andExpect(status().isUnauthorized());
        deliver("issues", body, "sha256=zz").andExpect(status().isUnauthorized());
        deliver("issues", body, sign(SECRET, body + " ")).andExpect(status().isUnauthorized());
    }

    @Test
    void pingIsAnswered() throws Exception {
        deliver("ping", "{\"zen\":\"Keep it logically awesome.\"}").andExpect(status().isOk())
                .andExpect(content().string("pong"));
    }

    @Test
    void aLabeledIssueBecomesATicketWithoutWaitingForThePoll() throws Exception {
        github.addIssue(props.repo(), 41, "Fresh issue", "", props.triggerLabel());

        deliver("issues", issueEvent("labeled", props.repo())).andExpect(status().isAccepted())
                .andExpect(content().string("poll scheduled"));

        long until = System.nanoTime() + 5_000_000_000L;
        while (tickets.attemptsFor(props.repo(), 41).isEmpty() && System.nanoTime() < until) {
            Thread.sleep(50);
        }
        assertThat(tickets.attemptsFor(props.repo(), 41)).hasSize(1);
    }

    @Test
    void eventsForOtherRepositoriesAreIgnored() throws Exception {
        github.addIssue(props.repo(), 42, "Should wait for the poll", "", props.triggerLabel());

        deliver("issues", issueEvent("labeled", "someone/else")).andExpect(status().isAccepted())
                .andExpect(content().string("ignored: other repository"));

        Thread.sleep(300);
        assertThat(tickets.attemptsFor(props.repo(), 42)).isEmpty();
    }

    @Test
    void aReviewWakesTheTicketWaitingOnThatPrInsteadOfWaitingForTheNextCheck() throws Exception {
        github.addIssue(props.repo(), 43, "Needs review", "fake-approval: pending", props.triggerLabel());
        poller.pollOnce();
        long id = tickets.attemptsFor(props.repo(), 43).getFirst().id();
        workers.newWorker("hook").drain(20);
        var t = tickets.get(id);
        assertThat(t.state()).isEqualTo(TicketState.AWAITING_APPROVAL);
        // Approval checks are an hour apart in tests: without the webhook, nothing would happen now.
        assertThat(workers.newWorker("hook").runOnce()).isFalse();

        github.setPullRequestStatus(t.prNumber(), PrStatus.APPROVED);
        deliver("pull_request_review", "{\"action\":\"submitted\",\"repository\":{\"full_name\":\"" + props.repo()
                + "\"},\"pull_request\":{\"number\":" + t.prNumber() + "}}")
                .andExpect(status().isAccepted()).andExpect(content().string("ticket " + id + " woken"));
        workers.newWorker("hook").drain(5);

        assertThat(tickets.get(id).state()).isEqualTo(TicketState.DONE);
    }

    @Test
    void aReviewOnAPrNoTicketWaitsOnIsHarmless() throws Exception {
        deliver("pull_request_review", "{\"action\":\"submitted\",\"repository\":{\"full_name\":\"" + props.repo()
                + "\"},\"pull_request\":{\"number\":9999}}")
                .andExpect(status().isAccepted()).andExpect(content().string("ignored: no ticket waits on PR #9999"));
    }
}
