package com.ticketfactory.intake;

import com.ticketfactory.FactoryProperties;
import com.ticketfactory.integration.GitHubClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Turns open issues carrying the trigger label into tickets. Safe to run repeatedly: one ticket per issue. */
@Component
public class GitHubPoller {

    private static final Logger log = LoggerFactory.getLogger(GitHubPoller.class);

    private final GitHubClient github;
    private final TicketIntake intake;
    private final FactoryProperties props;

    public GitHubPoller(GitHubClient github, TicketIntake intake, FactoryProperties props) {
        this.github = github;
        this.intake = intake;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${factory.poller.interval:PT10S}", initialDelayString = "PT2S")
    public void scheduledPoll() {
        if (!props.poller().enabled()) {
            return;
        }
        try {
            pollOnce();
        } catch (RuntimeException e) {
            log.warn("GitHub poll failed: {}", e.toString());
        }
    }

    /** Returns how many new tickets were created. */
    public int pollOnce() {
        int created = 0;
        for (GitHubClient.Issue issue : github.listOpenIssues(props.repo(), props.triggerLabel())) {
            if (intake.accept(issue.repo(), issue.number(), issue.title(), issue.body()).isPresent()) {
                created++;
                log.info("New ticket for {}#{}: {}", issue.repo(), issue.number(), issue.title());
            }
        }
        return created;
    }
}
