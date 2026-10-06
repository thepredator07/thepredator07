package com.ticketfactory.pipeline;

import static com.ticketfactory.ticket.TicketState.*;

import com.ticketfactory.FactoryProperties;
import com.ticketfactory.integration.AgentRunner;
import com.ticketfactory.integration.AgentRunner.AgentRequest;
import com.ticketfactory.integration.AgentRunner.AgentResult;
import com.ticketfactory.integration.BranchPolicy;
import com.ticketfactory.integration.ChecksRunner;
import com.ticketfactory.metrics.FactoryMetrics;
import java.util.Map;
import org.slf4j.MDC;
import com.ticketfactory.integration.GitHubClient;
import com.ticketfactory.integration.RateLimitedException;
import com.ticketfactory.integration.SandboxRunner;
import com.ticketfactory.integration.StepFailedException;
import com.ticketfactory.integration.UnrecoverableStepException;
import com.ticketfactory.integration.TicketContext;
import com.ticketfactory.queue.Job;
import com.ticketfactory.queue.JobContext;
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
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final ExecutorService agentExecutor;
    private final FactoryMetrics metrics;

    public TicketPipeline(TicketRepository tickets, TicketService transitions, GitHubClient github,
                          SandboxRunner sandbox, AgentRunner agent, ChecksRunner checks, FactoryProperties props,
                          Clock clock, @Qualifier("agentExecutor") ExecutorService agentExecutor,
                          FactoryMetrics metrics) {
        this.metrics = metrics;
        this.tickets = tickets;
        this.transitions = transitions;
        this.github = github;
        this.sandbox = sandbox;
        this.agent = agent;
        this.checks = checks;
        this.props = props;
        this.clock = clock;
        this.agentExecutor = agentExecutor;
    }

    /** Result of a single step: keep going, or park the job for a while. */
    private sealed interface Step {
        record Continue() implements Step {
        }

        record Wait(Duration delay, String note) implements Step {
        }
    }

    private static final Step CONTINUE = new Step.Continue();
    private static final Duration AGENT_WAIT_TICK = Duration.ofMillis(250);

    @Override
    public JobOutcome handle(Job job, JobContext context) {
        long id = job.ticketId();
        tickets.markStarted(id, clock.instant());
        while (true) {
            if (!context.stillOwned()) {
                return JobOutcome.abandon("lease lost before step");
            }
            Ticket ticket = tickets.get(id);
            if (ticket.state().isTerminal()) {
                cleanup(ticket);
                return JobOutcome.complete();
            }
            try {
                checkTimeout(ticket);
                long stepStarted = System.nanoTime();
                Step result;
                try {
                    result = step(ticket, context);
                } finally {
                    metrics.stepTook(ticket.state(), Duration.ofNanos(System.nanoTime() - stepStarted));
                }
                if (result instanceof Step.Wait w) {
                    return JobOutcome.reschedule(w.delay(), w.note());
                }
            } catch (LeaseLostException e) {
                return JobOutcome.abandon(e.getMessage());
            } catch (GuardrailExceededException e) {
                metrics.guardrailTripped(e.guardrail());
                failQuietly(id, e.getMessage());
            } catch (RateLimitedException e) {
                // Not the ticket's fault: wait for the limit to reset, without spending one of its retries.
                log.info("Ticket {} step {} rate limited, waiting {}: {}", id, ticket.state(), e.retryAfter(),
                        e.getMessage());
                return JobOutcome.reschedule(e.retryAfter(), "rate limited: " + e.getMessage());
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

    private Step step(Ticket t, JobContext context) {
        return switch (t.state()) {
            case RECEIVED -> prepareSandbox(t);
            case SANDBOX_READY -> {
                transitions.transition(t.id(), SANDBOX_READY, CODING, "Starting coding agent");
                yield CONTINUE;
            }
            case CODING -> runAgent(t, context);
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

    private Step runAgent(Ticket t, JobContext context) {
        FactoryProperties.Guardrails limits = props.guardrails();
        int turnsLeft = limits.maxTurns() - t.turns();
        BigDecimal budgetLeft = limits.maxCostUsd().subtract(t.costUsd());
        if (turnsLeft <= 0 || budgetLeft.signum() <= 0) {
            throw new GuardrailExceededException("budget", "no budget left before agent run (turns used " + t.turns()
                    + ", cost $" + t.costUsd() + ")");
        }
        AgentRequest request = new AgentRequest(context(t, t.branchName()), t.sandboxId(), prompt(t),
                t.lastFeedback(), turnsLeft, budgetLeft);

        long agentStarted = System.nanoTime();
        AgentResult result = callAgentWithTimeout(t, request, context);
        metrics.agentRun(Duration.ofNanos(System.nanoTime() - agentStarted), result.success(), result.costUsd(),
                result.inputTokens(), result.outputTokens(), result.turns());
        tickets.recordUsage(t.id(), result.inputTokens(), result.outputTokens(), result.costUsd(), result.turns());

        Ticket after = tickets.get(t.id());
        if (after.costUsd().compareTo(limits.maxCostUsd()) > 0) {
            throw new GuardrailExceededException("cost", "cost $" + after.costUsd().setScale(2, RoundingMode.HALF_UP)
                    + " exceeded limit $" + limits.maxCostUsd().setScale(2, RoundingMode.HALF_UP));
        }
        if (after.turns() > limits.maxTurns()) {
            throw new GuardrailExceededException("turns", "turns " + after.turns() + " exceeded limit " + limits.maxTurns());
        }
        if (!result.success()) {
            throw new StepFailedException(result.summary());
        }
        transitions.transition(t.id(), CODING, CHECKS, result.summary());
        return CONTINUE;
    }

    /**
     * Runs the agent on its own virtual thread and waits for it in short ticks, so the wait can end early when the
     * hard timeout passes, the ticket is cancelled, or this worker loses its lease. Cancelling the future interrupts the
     * agent's thread; the real runner then kills the agent's processes in the sandbox.
     */
    private AgentResult callAgentWithTimeout(Ticket t, AgentRequest request, JobContext context) {
        Instant deadline = clock.instant().plus(props.guardrails().hardTimeout().minus(elapsed(t)));
        Map<String, String> mdc = MDC.getCopyOfContextMap(); // keep ticket and job ids in the agent's log lines
        Future<AgentResult> future = agentExecutor.submit(() -> {
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                return agent.run(request);
            } finally {
                MDC.clear();
            }
        });
        try {
            while (true) {
                long leftMs = Duration.between(clock.instant(), deadline).toMillis();
                if (leftMs <= 0) {
                    future.cancel(true);
                    throw new GuardrailExceededException("timeout", "hard timeout " + props.guardrails().hardTimeout()
                            + " reached while the agent was running");
                }
                try {
                    return future.get(Math.min(leftMs, AGENT_WAIT_TICK.toMillis()), TimeUnit.MILLISECONDS);
                } catch (TimeoutException tick) {
                    if (!context.stillOwned()) {
                        future.cancel(true);
                        throw new LeaseLostException("lease lost while the agent was running");
                    }
                    if (tickets.get(t.id()).state().isTerminal()) {
                        future.cancel(true);
                        throw new ConcurrentTransitionException(t.id(), CODING, tickets.get(t.id()).state(), CHECKS);
                    }
                }
            }
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

    /** Thrown inside the pipeline when the worker discovers it no longer owns the job. */
    private static final class LeaseLostException extends RuntimeException {
        LeaseLostException(String message) {
            super(message);
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
        // Push the branch first: on real GitHub a PR needs it to exist. Idempotent, so retries are safe.
        sandbox.publishBranch(context(t, t.branchName()), t.sandboxId());
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
        if (e instanceof UnrecoverableStepException) {
            failQuietly(t.id(), t.state() + " failed: " + e.getMessage());
            return null;
        }
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
            throw new GuardrailExceededException("timeout", "hard timeout " + props.guardrails().hardTimeout() + " exceeded");
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
