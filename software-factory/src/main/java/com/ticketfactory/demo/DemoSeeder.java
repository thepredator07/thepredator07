package com.ticketfactory.demo;

import static com.ticketfactory.ticket.TicketState.*;

import com.ticketfactory.FactoryProperties;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.integration.BranchPolicy;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.TicketState;
import com.ticketfactory.ticket.TicketStateMachine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code demo} profile: on first start (empty database) writes 18 finished tickets with realistic history so the
 * dashboard has data, and opens 4 issues on the fake GitHub that then flow through the real pipeline live.
 * Every seeded history is checked against {@link TicketStateMachine}, so demo data can never show an illegal path.
 */
@Component
@Profile("demo")
public class DemoSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);

    private final JdbcClient jdbc;
    private final TicketRepository tickets;
    private final FakeGitHubClient github;
    private final FactoryProperties props;
    private final Clock clock;

    public DemoSeeder(JdbcClient jdbc, TicketRepository tickets, FakeGitHubClient github, FactoryProperties props,
                      Clock clock) {
        this.jdbc = jdbc;
        this.tickets = tickets;
        this.github = github;
        this.props = props;
        this.clock = clock;
    }

    /** One historical ticket: the path it took, how many agent runs, and why it ended. */
    record Scenario(String title, String body, List<TicketState> path, int agentRuns, String endReason) {
    }

    private static final List<TicketState> HAPPY =
            List.of(RECEIVED, SANDBOX_READY, CODING, CHECKS, PR_OPENED, AWAITING_APPROVAL, DONE);
    private static final List<TicketState> ONE_FIX =
            List.of(RECEIVED, SANDBOX_READY, CODING, CHECKS, CODING, CHECKS, PR_OPENED, AWAITING_APPROVAL, DONE);
    private static final List<TicketState> TWO_FIXES = List.of(RECEIVED, SANDBOX_READY, CODING, CHECKS, CODING,
            CHECKS, CODING, CHECKS, PR_OPENED, AWAITING_APPROVAL, DONE);

    static List<Scenario> scenarios() {
        return List.of(
                new Scenario("Add pagination to GET /api/orders", "The orders endpoint returns every row.", HAPPY, 1, "PR approved"),
                new Scenario("Fix NPE in InvoiceService when customer has no address", "Stack trace attached.", HAPPY, 1, "PR approved"),
                new Scenario("Upgrade Jackson to 2.17 and fix deprecations", "", ONE_FIX, 2, "PR approved"),
                new Scenario("Add request ID to every log line", "Use MDC.", HAPPY, 1, "PR approved"),
                new Scenario("Validate email format on signup form", "", HAPPY, 1, "PR approved"),
                new Scenario("Cache exchange rates for 10 minutes", "Rates API is rate limited.", ONE_FIX, 2, "PR approved"),
                new Scenario("Return 404 instead of 500 for unknown product IDs", "", HAPPY, 1, "PR approved"),
                new Scenario("Add index on payments(created_at)", "Monthly report is slow.", HAPPY, 1, "PR approved"),
                new Scenario("Make CSV export respect the user's timezone", "", TWO_FIXES, 3, "PR approved"),
                new Scenario("Remove unused feature flag NEW_CHECKOUT", "", HAPPY, 1, "PR approved"),
                new Scenario("Add retry with backoff to webhook delivery", "", ONE_FIX, 2, "PR approved"),
                new Scenario("Document the /health endpoint in the README", "", HAPPY, 1, "PR approved"),
                new Scenario("Rewrite the reporting module in a new framework", "",
                        List.of(RECEIVED, SANDBOX_READY, CODING, FAILED), 1, "guardrail: cost $2.31 exceeded limit $2.00"),
                new Scenario("Fix flaky OrderSyncIntegrationTest", "",
                        List.of(RECEIVED, SANDBOX_READY, CODING, CHECKS, CODING, CHECKS, CODING, CHECKS, CODING, CHECKS, FAILED),
                        4, "Checks still failing after 3 retries"),
                new Scenario("Support SAML login", "",
                        List.of(RECEIVED, FAILED), 0, "RECEIVED failed after 3 retries: fake sandbox: container failed to start (simulated)"),
                new Scenario("Migrate all dates to java.time", "",
                        List.of(RECEIVED, SANDBOX_READY, CODING, FAILED), 1, "guardrail: turns 46 exceeded limit 40"),
                new Scenario("Add dark mode to admin panel", "",
                        List.of(RECEIVED, SANDBOX_READY, CODING, CHECKS, PR_OPENED, AWAITING_APPROVAL, CANCELLED), 1,
                        "PR #1007 closed without merging"),
                new Scenario("Rename customer_ref column", "",
                        List.of(RECEIVED, SANDBOX_READY, CANCELLED), 0, "Cancelled from the dashboard"));
    }

    /** Issues opened on the fake GitHub at startup; these go through the real pipeline while you watch. */
    static List<String[]> liveIssues() {
        return List.of(
                new String[]{"Add rate limiting to the public API", "Limit to 100 req/min per key.\n\nfake-agent-delay: PT8S"},
                new String[]{"Fix rounding in tax calculation", "Totals are off by a cent.\n\nfake-checks: fail-then-succeed 1\nfake-agent-delay: PT3S"},
                new String[]{"Add audit log for admin actions", "Needs a human review.\n\nfake-approval: pending\nfake-agent-delay: PT2S"},
                new String[]{"Port the whole frontend to a new framework", "fake-agent-cost: 3.75\nfake-agent-delay: PT2S"});
    }

    @Override
    public void run(ApplicationArguments args) {
        if (tickets.count() == 0) {
            seedHistory();
        } else {
            log.info("Demo: database already has tickets, not seeding history");
        }
        int issue = 900;
        for (String[] live : liveIssues()) {
            github.addIssue(props.repo(), issue++, live[0], live[1], props.triggerLabel());
        }
        log.info("Demo: opened {} live issues on the fake GitHub", liveIssues().size());
    }

    @Transactional
    public void seedHistory() {
        Random rnd = new Random(42);
        Instant now = clock.instant();
        List<Scenario> all = scenarios();
        for (int i = 0; i < all.size(); i++) {
            Scenario s = all.get(i);
            validate(s.path());
            Instant created = now.minus(Duration.ofHours(6L * (all.size() - i))).minusSeconds(rnd.nextInt(3600));
            insert(i + 1, s, created, rnd);
        }
        log.info("Demo: seeded {} historical tickets", all.size());
    }

    static void validate(List<TicketState> path) {
        if (path.getFirst() != RECEIVED) {
            throw new IllegalStateException("demo path must start at RECEIVED: " + path);
        }
        for (int i = 0; i + 1 < path.size(); i++) {
            TicketStateMachine.validate(path.get(i), path.get(i + 1));
        }
    }

    private void insert(int issueNumber, Scenario s, Instant created, Random rnd) {
        int turns = 0;
        long in = 0;
        long out = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (int run = 0; run < s.agentRuns(); run++) {
            int t = 4 + rnd.nextInt(9);
            turns += t;
            in += t * (3000L + rnd.nextInt(2500));
            out += t * (500L + rnd.nextInt(600));
            cost = cost.add(BigDecimal.valueOf(0.06 + rnd.nextDouble() * 0.30));
        }
        if (s.endReason().startsWith("guardrail: cost")) {
            cost = new BigDecimal("2.31");
        }
        if (s.endReason().startsWith("guardrail: turns")) {
            turns = 46;
        }
        cost = cost.setScale(4, RoundingMode.HALF_UP);
        int retries = (int) s.path().stream().filter(st -> st == CODING).count() - 1;
        if (s.path().equals(List.of(RECEIVED, FAILED))) {
            retries = 4;
        }
        retries = Math.max(0, retries);

        List<Instant> times = new ArrayList<>();
        Instant t = created;
        for (TicketState st : s.path()) {
            times.add(t);
            t = t.plusSeconds(switch (st) {
                case RECEIVED -> 2 + rnd.nextInt(5);
                case SANDBOX_READY -> 1 + rnd.nextInt(3);
                case CODING -> 60 + rnd.nextInt(420);
                case CHECKS -> 40 + rnd.nextInt(150);
                case PR_OPENED -> 1;
                case AWAITING_APPROVAL -> 300 + rnd.nextInt(3600);
                default -> 0;
            });
        }
        Instant finished = times.getLast();
        TicketState end = s.path().getLast();
        boolean reachedPr = s.path().contains(PR_OPENED);
        int prNumber = 1000 + issueNumber;

        long id = jdbc.sql("""
                        INSERT INTO tickets (repo, issue_number, title, body, state, branch_name, sandbox_id, pr_number,
                            pr_url, failure_reason, tokens_input, tokens_output, cost_usd, turns, retries, created_at,
                            updated_at, started_at, finished_at, duration_ms)
                        VALUES (:repo, :issue, :title, :body, :state, :branch, :sbx, :pr, :prUrl, :reason, :in, :out,
                            :cost, :turns, :retries, :created, :finished, :created, :finished, :duration)
                        RETURNING id""")
                .param("repo", props.repo()).param("issue", issueNumber).param("title", s.title())
                .param("body", s.body()).param("state", end.name())
                .param("branch", null).param("sbx", null)
                .param("pr", reachedPr ? prNumber : null)
                .param("prUrl", reachedPr ? "https://github.com/" + props.repo() + "/pull/" + prNumber : null)
                .param("reason", end == FAILED ? s.endReason() : null)
                .param("in", in).param("out", out).param("cost", cost).param("turns", turns)
                .param("retries", retries)
                .param("created", Timestamp.from(created)).param("finished", Timestamp.from(finished))
                .param("duration", Duration.between(created, finished).toMillis())
                .query(Long.class).single();

        boolean hasSandbox = s.path().contains(SANDBOX_READY);
        jdbc.sql("UPDATE tickets SET branch_name = :b, sandbox_id = :s WHERE id = :id")
                .param("b", hasSandbox ? BranchPolicy.branchFor(id) : null)
                .param("s", hasSandbox ? "fake-sbx-" + id : null).param("id", id).update();

        int fixes = 0;
        for (int i = 0; i < s.path().size(); i++) {
            TicketState from = i == 0 ? null : s.path().get(i - 1);
            TicketState to = s.path().get(i);
            if (from == CHECKS && to == CODING) {
                fixes++;
            }
            jdbc.sql("""
                            INSERT INTO ticket_transitions (ticket_id, from_state, to_state, reason, created_at)
                            VALUES (:id, :from, :to, :reason, :at)""")
                    .param("id", id).param("from", from == null ? null : from.name()).param("to", to.name())
                    .param("reason", reason(from, to, s, id, prNumber, fixes))
                    .param("at", Timestamp.from(times.get(i))).update();
        }
    }

    private static String reason(TicketState from, TicketState to, Scenario s, long id, int pr, int fixes) {
        if (to.isTerminal() && to != DONE) {
            return s.endReason();
        }
        return switch (to) {
            case RECEIVED -> "Picked up issue";
            case SANDBOX_READY -> "Sandbox fake-sbx-" + id + " on branch factory/" + id;
            case CODING -> from == CHECKS ? "Checks failed, sending output back to the agent (retry " + fixes + ")"
                    : "Starting coding agent";
            case CHECKS -> from == CODING && fixes > 0 ? "fake agent: fixed failing checks"
                    : "fake agent: implemented '" + s.title() + "'";
            case PR_OPENED -> "Checks passed; opened PR #" + pr;
            case AWAITING_APPROVAL -> "Review requested";
            case DONE -> "PR #" + pr + " approved";
            default -> s.endReason();
        };
    }
}
