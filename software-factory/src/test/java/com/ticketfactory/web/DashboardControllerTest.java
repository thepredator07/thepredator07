package com.ticketfactory.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketfactory.AbstractIntegrationTest;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.intake.GitHubPoller;
import com.ticketfactory.queue.WorkerPool;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.TicketState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class DashboardControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired FakeGitHubClient github;
    @Autowired GitHubPoller poller;
    @Autowired WorkerPool workers;
    @Autowired TicketRepository tickets;

    @BeforeEach
    void reset() {
        github.reset();
    }

    private long submitAndRun(int issue, String title, String body) {
        github.addIssue("example-org/example-repo", issue, title, body, "factory");
        poller.pollOnce();
        workers.newWorker("web").drain(100);
        return tickets.findAll(null, 100).stream().filter(t -> t.issueNumber() == issue).findFirst()
                .orElseThrow().id();
    }

    @Test
    void rootRedirectsToTickets() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/tickets"));
    }

    @Test
    void emptyTicketListRenders() throws Exception {
        mvc.perform(get("/tickets")).andExpect(status().isOk())
                .andExpect(content().string(containsString("No tickets yet")));
    }

    @Test
    void ticketListShowsTicketsAndFilters() throws Exception {
        submitAndRun(1, "Add CSV export", "");
        submitAndRun(2, "Broken thing", "fake-agent: fail");

        mvc.perform(get("/tickets")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Add CSV export")))
                .andExpect(content().string(containsString("Broken thing")))
                .andExpect(content().string(containsString("50.0%")));

        mvc.perform(get("/tickets").param("state", "FAILED")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Broken thing")))
                .andExpect(content().string(not(containsString("Add CSV export"))));
    }

    @Test
    void ticketDetailShowsHistoryUsageAndPr() throws Exception {
        long id = submitAndRun(1, "Add CSV export", "");

        mvc.perform(get("/tickets/" + id)).andExpect(status().isOk())
                .andExpect(content().string(containsString("State history")))
                .andExpect(content().string(containsString("SANDBOX_READY")))
                .andExpect(content().string(containsString("AWAITING_APPROVAL")))
                .andExpect(content().string(containsString("factory/" + id)))
                .andExpect(content().string(containsString("/pull/")))
                .andExpect(content().string(containsString("24,000")));
    }

    @Test
    void unknownTicketIs404() throws Exception {
        mvc.perform(get("/tickets/9999")).andExpect(status().isNotFound());
    }

    @Test
    void statsPageShowsNumbers() throws Exception {
        submitAndRun(1, "Good", "");
        submitAndRun(2, "Bad", "fake-agent-cost: 9.00");

        mvc.perform(get("/stats")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Success rate")))
                .andExpect(content().string(containsString("50.0%")))
                .andExpect(content().string(containsString("$9.12")))
                .andExpect(content().string(containsString("guardrail: cost $9")));
    }

    @Test
    void cancelAndApproveActions() throws Exception {
        long waiting = submitAndRun(1, "Needs review", "fake-approval: pending");

        mvc.perform(post("/tickets/" + waiting + "/approve").with(csrf()))
                .andExpect(status().is3xxRedirection());
        workers.newWorker("web").drain(10);
        org.assertj.core.api.Assertions.assertThat(tickets.get(waiting).state()).isEqualTo(TicketState.DONE);

        long other = submitAndRun(2, "Cancel me", "fake-approval: pending");
        mvc.perform(post("/tickets/" + other + "/cancel").with(csrf())).andExpect(status().is3xxRedirection());
        org.assertj.core.api.Assertions.assertThat(tickets.get(other).state()).isEqualTo(TicketState.CANCELLED);
    }

    @Test
    void theTicketListIsPaged() throws Exception {
        for (int i = 1; i <= 55; i++) {
            jdbc.sql("INSERT INTO tickets (repo, issue_number, title, body, state, attempt, triggered_at, created_at,"
                            + " updated_at) VALUES ('acme/app', :n, :t, '', 'DONE', 1, now(), now() + :n * interval"
                            + " '1 second', now())")
                    .param("n", i).param("t", "Ticket number " + i).update();
        }
        mvc.perform(get("/tickets")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Ticket number 55")))
                .andExpect(content().string(containsString("Ticket number 6<")))
                .andExpect(content().string(not(containsString("Ticket number 5<"))))
                .andExpect(content().string(containsString("Older")))
                .andExpect(content().string(not(containsString("Newer"))));
        mvc.perform(get("/tickets").param("page", "2")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Ticket number 5<")))
                .andExpect(content().string(containsString("Ticket number 1<")))
                .andExpect(content().string(not(containsString("Ticket number 6<"))))
                .andExpect(content().string(containsString("Newer")))
                .andExpect(content().string(not(containsString("Older"))));
    }

    /** Another site can't make a visitor's browser cancel tickets: forms without the CSRF token are refused. */
    @Test
    void actionsWithoutACsrfTokenAreRefused() throws Exception {
        long waiting = submitAndRun(3, "Keep me", "fake-approval: pending");

        mvc.perform(post("/tickets/" + waiting + "/cancel")).andExpect(status().isForbidden());
        mvc.perform(post("/tickets/" + waiting + "/approve")).andExpect(status().isForbidden());

        org.assertj.core.api.Assertions.assertThat(tickets.get(waiting).state())
                .isEqualTo(TicketState.AWAITING_APPROVAL);
    }

    @Test
    void ticketPageFormsCarryTheCsrfToken() throws Exception {
        long waiting = submitAndRun(4, "Has forms", "fake-approval: pending");
        mvc.perform(get("/tickets/" + waiting)).andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    void fakeIssueApiCreatesIssueForThePoller() throws Exception {
        mvc.perform(post("/api/fake/issues").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\": 77, \"title\": \"From the API\", \"body\": \"\"}"))
                .andExpect(status().isAccepted());
        poller.pollOnce();
        org.assertj.core.api.Assertions.assertThat(tickets.findAll(null, 10))
                .extracting(t -> t.title()).containsExactly("From the API");

        mvc.perform(post("/api/fake/issues").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"number\": 0, \"title\": \"\"}"))
                .andExpect(status().isBadRequest());
    }
}
