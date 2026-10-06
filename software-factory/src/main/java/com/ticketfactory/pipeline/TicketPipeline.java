package com.ticketfactory.pipeline;

import static com.ticketfactory.ticket.TicketState.*;

import com.ticketfactory.FactoryProperties;
import com.ticketfactory.integration.AgentRunner;
import com.ticketfactory.integration.AgentRunner.AgentRequest;
import com.ticketfactory.integration.AgentRunner.AgentResult;
import com.ticketfactory.integration.BranchPolicy;
import com.ticketfactory.integration.ChecksRunner;
import com.ticketfactory.integration.GitHubClient;
import com.ticketfactory.integration.SandboxRunner;
import com.ticketfactory.integration.StepFailedException;
import com.ticketfactory.integration.TicketContext;
import com.ticketfactory.queue.Job;
import com.ticketfactory.queue.JobHandler;
import com.ticketfactory.queue.JobOutcome;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.TicketService;
import com.ticketfactory.ticket.TicketService.ConcurrentTransitionException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Drives one ticket through the state machine. Each call resumes from the ticket's persisted state, so a job
 * that is retried, rescheduled, or recovered after a worker crash continues where it left off.
 */
@Component
public class TicketPipeline implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(TicketPipeline.class);

    private final TicketRepository tickets;
    private final TicketService transitions;
    private final GitHubClient github;
    private final SandboxRunner sandbox;
    private final AgentRunner agent;
    private final ChecksRunner checks;
    private final FactoryProperties props;
    private final Clock clock;
    private final ExecutorService agentExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public TicketPipeline(TicketRepository tickets, TicketService transitions, GitHubClient github,
                          SandboxRunner sandbox, AgentRunner agent, ChecksRunner checks, FactoryProperties props,
                          Clock clock) {
        this.tickets = tickets;
        this.transitions = transitions;
        this.github = github;
        this.sandbox = sandbox;
        this.agent = agent;
        this.checks = checks;
        this.props = props;
        this.clock = clock;
    }

    /** Result of a single step: keep going, or park the job for a while. */
    private sealed interface Step {
        record Continue() implements Step {
        }

        record Wait(Duration delay, String note) implements Step {
        }
    }

    private static final Step CONTINUE = new Step.Continue();

    @Override
    public JobOutcome handle(Job job) {
        long id = job.ticketId();
        tickets.markStarted(id, clock.instant());
        while (true) {
            Ticket ticket = tickets.get(id);
            if (ticket.state().isTerminal()) {
                cleanup(ticket);
                return JobOutcome.complete();
            }
            try {
                checkTimeout(ticket);
                if (step(ticket) instanceof Step.Wait w) {
                    return JobOutcome.reschedule(w.delay(), w.note());
                }
            } catch (GuardrailExceededException e) {
                failQuietly(id, e.getMessage());
            } catch (StepFailedException e) {
                JobOutcome outcome = onStepFailure(ticket, e);
                if (outcome != null) {
                    return outcome;
                }
            } catch (ConcurrentTransitionException e) {
                // Someone else moved the ticket (usually a user cancelling). Re-read and re-evaluate.
                log.info("Ticket {} changed under us: {}", id, e.getMessage());
            }
        }
    }

    private Step step(Ticket t) {
        return switch (t.state()) {
            case RECEIVED -> prepareSandbox(t);
            case SANDBOX_READY -> {
                transitions.transition(t.id(), SANDBOX_READY, CODING, "Starting coding agent");
                yield CONTINUE;
            }
            case CODING -> runAgent(t);
            case CHECKS -> runChecks(t);
            case PR_OPENED -> {
                github.commentOnIssue(t.repo(), t.issueNumber(), "Opened " + t.prUrl() + " for review.");
                transitions.transition(t.id(), PR_OPENED, AWAITING_APPROVAL, "Review requested");
                yield CONTINUE;
            }
            case AWAITING_APPROVAL -> checkApproval(t);
            case DONE, FAILED, CANCELLED -> throw new IllegalStateException("terminal state " + t.state());
        };
    }

    private Step prepareSandbox(Ticket t) {
        String branch = BranchPolicy.branchFor(t.id());
        SandboxRunner.Sandbox sbx = sandbox.prepare(context(t, branch));
        tickets.setSandbox(t.id(), sbx.id(), branch);
        transitions.transition(t.id(), RECEIVED, SANDBOX_READY, "Sandbox " + sbx.id() + " on branch " + branch);
        return CONTINUE;
    }

    private Step runAgent(Ticket t) {
        FactoryProperties.Guardrails limits = props.guardrails();
        int turnsLeft = limits.maxTurns() - t.turns();
        BigDecimal budgetLeft = limits.maxCostUsd().subtract(t.costUsd());
        if (turnsLeft <= 0 || budgetLeft.signum() <= 0) {
            throw new GuardrailExceededException("no budget left before agent run (turns used " + t.turns()
                    + ", cost $" + t.costUsd() + ")");
        }
        AgentRequest request = new AgentRequest(context(t, t.branchName()), t.sandboxId(), prompt(t),
                t.lastFeedback(), turnsLeft, budgetLeft);

        AgentResult result = callAgentWithTimeout(t, request);
        tickets.recordUsage(t.id(), result.inputTokens(), result.outputTokens(), result.costUsd(), result.turns());

        Ticket after = tickets.get(t.id());
        if (after.costUsd().compareTo(limits.maxCostUsd()) > 0) {
            throw new GuardrailExceededException("cost $" + after.costUsd().setScale(2, RoundingMode.HALF_UP)
                    + " exceeded limit $" + limits.maxCostUsd().setScale(2, RoundingMode.HALF_UP));
        }
        if (after.turns() > limits.maxTurns()) {
            throw new GuardrailExceededException("turns " + after.turns() + " exceeded limit " + limits.maxTurns());
        }
        if (!result.success()) {
            throw new StepFailedException(result.summary());
        }
        transitions.transition(t.id(), CODING, CHECKS, result.summary());
        return CONTINUE;
    }

    private AgentResult callAgentWithTimeout(Ticket t, AgentRequest request) {
        Duration left = props.guardrails().hardTimeout().minus(elapsed(t));
        Future<AgentResult> future = agentExecutor.submit(() -> agent.run(request));
        try {
            return future.get(Math.max(1, left.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new GuardrailExceededException("hard timeout " + props.guardrails().hardTimeout()
                    + " reached while the agent was running");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new StepFailedException("agent crashed: " + cause, cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new StepFailedException("interrupted while waiting for agent", e);
        }
    }

    private Step runChecks(Ticket t) {
        ChecksRunner.ChecksResult result = checks.run(context(t, t.branchName()), t.sandboxId());
        if (!result.passed()) {
            tickets.setFeedback(t.id(), result.output());
            int retries = tickets.incrementRetries(t.id());
            if (retries > props.guardrails().maxRetries()) {
                transitions.transition(t.id(), CHECKS, FAILED,
                        "Checks still failing after " + props.guardrails().maxRetries() + " retries");
            } else {
                transitions.transition(t.id(), CHECKS, CODING,
                        "Checks failed, sending output back to the agent (retry " + retries + ")");
            }
            return CONTINUE;
        }
        if (t.prNumber() == null) {
            GitHubClient.PullRequest pr = github.openPullRequest(new GitHubClient.PullRequestRequest(
                    t.repo(), t.issueNumber(), t.branchName(), props.baseBranch(),
                    "[factory] " + t.title(),
                    "Closes #" + t.issueNumber() + "\n\nOpened automatically by the software factory."));
            tickets.setPullRequest(t.id(), pr.number(), pr.url());
            t = tickets.get(t.id());
        }
        transitions.transition(t.id(), CHECKS, PR_OPENED, "Checks passed; opened PR #" + t.prNumber());
        return CONTINUE;
    }

    private Step checkApproval(Ticket t) {
        return switch (github.getPullRequestStatus(t.repo(), t.prNumber())) {
            case APPROVED -> {
                transitions.transition(t.id(), AWAITING_APPROVAL, DONE, "PR #" + t.prNumber() + " approved");
                yield CONTINUE;
            }
            case CLOSED -> {
                transitions.transition(t.id(), AWAITING_APPROVAL, CANCELLED,
                        "PR #" + t.prNumber() + " closed without merging");
                yield CONTINUE;
            }
            case PENDING -> new Step.Wait(props.worker().approvalPollInterval(), "waiting for PR approval");
        };
    }

    /** Returns the job outcome if the job should stop here, or null to keep looping. */
    private JobOutcome onStepFailure(Ticket t, StepFailedException e) {
        int retries = tickets.incrementRetries(t.id());
        int max = props.guardrails().maxRetries();
        if (retries > max) {
            failQuietly(t.id(), t.state() + " failed after " + max + " retries: " + e.getMessage());
            return null;
        }
        log.info("Ticket {} step {} failed (retry {}/{}): {}", t.id(), t.state(), retries, max, e.getMessage());
        Duration backoff = props.worker().retryBackoff().multipliedBy(retries);
        return JobOutcome.reschedule(backoff, "retry " + retries + "/" + max + " after: " + e.getMessage());
    }

    private void checkTimeout(Ticket t) {
        if (t.state() == AWAITING_APPROVAL) {
            return; // waiting on a human does not count against the agent's time budget
        }
        if (elapsed(t).compareTo(props.guardrails().hardTimeout()) > 0) {
            throw new GuardrailExceededException("hard timeout " + props.guardrails().hardTimeout() + " exceeded");
        }
    }

    private void failQuietly(long id, String reason) {
        Ticket t = tickets.get(id);
        if (t.state().isTerminal()) {
            return;
        }
        try {
            transitions.transition(id, t.state(), FAILED, reason);
        } catch (ConcurrentTransitionException e) {
            log.info("Ticket {} changed while failing it: {}", id, e.getMessage());
        }
    }

    private void cleanup(Ticket t) {
        if (t.sandboxId() != null) {
            try {
                sandbox.destroy(t.sandboxId());
            } catch (RuntimeException e) {
                log.warn("Could not destroy sandbox {}: {}", t.sandboxId(), e.toString());
            }
        }
    }

    private Duration elapsed(Ticket t) {
        return Duration.between(t.startedAt() != null ? t.startedAt() : t.createdAt(), clock.instant());
    }

    private static TicketContext context(Ticket t, String branch) {
        return new TicketContext(t.id(), t.repo(), t.issueNumber(), t.title(), t.body(), branch);
    }

    private static String prompt(Ticket t) {
        return "Resolve GitHub issue #" + t.issueNumber() + " in " + t.repo() + ": " + t.title() + "\n\n" + t.body();
    }

}
