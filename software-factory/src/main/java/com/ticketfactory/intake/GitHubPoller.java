package com.ticketfactory.intake;

import com.ticketfactory.FactoryProperties;
import com.ticketfactory.integration.GitHubClient;
import com.ticketfactory.ticket.Ticket;
import com.ticketfactory.ticket.TicketRepository;
import com.ticketfactory.ticket.TicketService;
import com.ticketfactory.ticket.TicketState;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Syncs tickets with the issue tracker on every poll:
 * <ul>
 *   <li>an open issue with the trigger label and no running attempt starts one, if its trigger is new (re-applying
 *       the label to a finished issue starts the next attempt);</li>
 *   <li>a running attempt whose issue was closed or lost the label is cancelled, as long as no PR exists yet, once
 *       the issue has been missing for {@code missing-polls-before-cancel} polls in a row (GitHub's listing lags
 *       behind writes, so one miss can be stale).</li>
 * </ul>
 */
@Component
public class GitHubPoller {

    private static final Logger log = LoggerFactory.getLogger(GitHubPoller.class);

    /**
     * States where the work hasn't produced a PR yet. Once a PR is open, its fate decides the ticket: merging a PR with
     * "Closes #N" closes the issue, which must not cancel the ticket that is about to be marked DONE.
     */
    private static final Set<TicketState> CANCELLABLE_BY_ISSUE =
            EnumSet.of(TicketState.RECEIVED, TicketState.SANDBOX_READY, TicketState.CODING, TicketState.CHECKS);

    public record PollResult(int created, int cancelled) {
    }

    private final GitHubClient github;
    private final TicketIntake intake;
    private final TicketRepository tickets;
    private final TicketService ticketService;
    private final FactoryProperties props;
    /** Consecutive polls each running ticket's issue has been missing from the listing. */
    private final java.util.Map<Long, Integer> misses = new java.util.concurrent.ConcurrentHashMap<>();

    public GitHubPoller(GitHubClient github, TicketIntake intake, TicketRepository tickets,
                        TicketService ticketService, FactoryProperties props) {
        this.github = github;
        this.intake = intake;
        this.tickets = tickets;
        this.ticketService = ticketService;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${factory.poller.interval:PT10S}", initialDelayString = "PT2S")
    public void scheduledPoll() {
        if (!props.poller().enabled()) {
            return;
        }
        try {
            poll();
        } catch (RuntimeException e) {
            log.warn("GitHub poll failed: {}", e.toString());
        }
    }

    /** Returns how many new attempts were started. */
    public int pollOnce() {
        return poll().created();
    }

    /**
     * One poll at a time: a webhook can ask for a poll while the scheduled one runs, and two overlapping polls would
     * each count the same missing issue, defeating the "missing twice in a row" rule.
     */
    public synchronized PollResult poll() {
        List<GitHubClient.Issue> open = github.listOpenIssues(props.repo(), props.triggerLabel());
        int created = 0;
        for (GitHubClient.Issue issue : open) {
            if (intake.accept(issue).isPresent()) {
                created++;
                log.info("New attempt for {}#{}: {}", issue.repo(), issue.number(), issue.title());
            }
        }
        Set<Integer> stillWanted = open.stream().map(GitHubClient.Issue::number).collect(Collectors.toSet());
        int cancelled = 0;
        Set<Long> active = new java.util.HashSet<>();
        for (Ticket t : tickets.findActive(props.repo())) {
            active.add(t.id());
            if (stillWanted.contains(t.issueNumber()) || !CANCELLABLE_BY_ISSUE.contains(t.state())) {
                misses.remove(t.id());
                continue;
            }
            int missed = misses.merge(t.id(), 1, Integer::sum);
            if (missed < props.poller().missingPollsBeforeCancel()) {
                log.info("Issue {}#{} missing from the listing ({} of {} polls before cancelling ticket {})",
                        t.repo(), t.issueNumber(), missed, props.poller().missingPollsBeforeCancel(), t.id());
                continue;
            }
            misses.remove(t.id());
            if (cancel(t)) {
                cancelled++;
            }
        }
        misses.keySet().retainAll(active);
        return new PollResult(created, cancelled);
    }

    private boolean cancel(Ticket t) {
        try {
            ticketService.transition(t.id(), t.state(), TicketState.CANCELLED,
                    "Issue #" + t.issueNumber() + " was closed or lost the '" + props.triggerLabel() + "' label");
            log.info("Cancelled ticket {}: issue {}#{} no longer open with the trigger label", t.id(), t.repo(),
                    t.issueNumber());
            return true;
        } catch (TicketService.ConcurrentTransitionException e) {
            // The pipeline moved it meanwhile; the next poll looks again.
            return false;
        }
    }
}
