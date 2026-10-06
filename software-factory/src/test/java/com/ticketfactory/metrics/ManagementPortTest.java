package com.ticketfactory.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketfactory.FactoryProperties;
import com.ticketfactory.TestcontainersConfiguration;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.intake.GitHubPoller;
import com.ticketfactory.queue.WorkerPool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/**
 * The production layout on a real server: dashboard (with sign-in) on one port, metrics on the management port, which
 * Prometheus can scrape without signing in. Also checks the factory's own metrics and the ticket id in log lines.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "factory.security.mode=basic",
        "factory.security.admin-password=correct-horse-battery"})
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
@AutoConfigureObservability // Spring Boot tests turn metrics export off unless asked
class ManagementPortTest {

    @LocalServerPort int appPort;
    @LocalManagementPort int managementPort;
    @Autowired FakeGitHubClient github;
    @Autowired GitHubPoller poller;
    @Autowired WorkerPool workers;
    @Autowired FactoryProperties props;
    @Autowired JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void metricsAreOnTheManagementPortOnlyAndCountWhatHappened(CapturedOutput output) throws Exception {
        jdbc.sql("TRUNCATE jobs, ticket_transitions, tickets RESTART IDENTITY CASCADE").update();
        github.reset();
        github.addIssue(props.repo(), 7, "Metered", "", props.triggerLabel());
        github.addIssue(props.repo(), 8, "Too expensive", "fake-agent-cost: 3.50", props.triggerLabel());
        poller.pollOnce();
        workers.newWorker("metrics").drain(20);
        long id = jdbc.sql("SELECT id FROM tickets WHERE issue_number = 7").query(Long.class).single();

        HttpResponse<String> scrape = get(managementPort, "/actuator/prometheus");
        assertThat(scrape.statusCode()).as("no sign-in on the management port").isEqualTo(200);
        assertThat(scrape.body())
                .contains("factory_tickets_finished_total{outcome=\"DONE\"} 1.0")
                .contains("factory_tickets_finished_total{outcome=\"FAILED\"} 1.0")
                .contains("factory_guardrail_trips_total{guardrail=\"cost\"} 1.0")
                .containsPattern("factory_tickets\\{state=\"DONE\"\\} 1\\.0")
                .contains("factory_jobs{status=\"DONE\"}")
                .contains("factory_agent_turns_total")
                .contains("factory_step_seconds_count{step=\"CODING\"}");
        assertThat(get(managementPort, "/actuator/health").statusCode()).isEqualTo(200);

        HttpResponse<String> onAppPort = get(appPort, "/actuator/prometheus");
        assertThat(onAppPort.statusCode()).as("not on the public port").isNotEqualTo(200);
        assertThat(onAppPort.body()).doesNotContain("factory_");
        assertThat(get(appPort, "/tickets").statusCode()).as("dashboard still needs sign-in").isEqualTo(401);

        assertThat(output.getOut()).as("ticket and job ids in the worker's log lines")
                .containsPattern("\\[t:" + id + " j:\\d+\\]");
    }
}
