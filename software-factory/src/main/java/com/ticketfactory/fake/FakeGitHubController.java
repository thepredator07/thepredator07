package com.ticketfactory.fake;

import com.ticketfactory.FactoryProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Lets you "open" an issue on the fake GitHub so you can watch it flow through the factory. Phase 1 only. */
@RestController
@RequestMapping("/api/fake")
@ConditionalOnProperty(name = "factory.integrations", havingValue = "fake", matchIfMissing = true)
public class FakeGitHubController {

    private final FakeGitHubClient github;
    private final FactoryProperties props;

    public FakeGitHubController(FakeGitHubClient github, FactoryProperties props) {
        this.github = github;
        this.props = props;
    }

    public record NewIssue(@Positive int number, @NotBlank String title, String body, List<String> labels) {
    }

    @PostMapping("/issues")
    public ResponseEntity<Map<String, Object>> addIssue(@Valid @RequestBody NewIssue issue) {
        List<String> labels = issue.labels() == null ? List.of(props.triggerLabel()) : issue.labels();
        github.addIssue(props.repo(), issue.number(), issue.title(), issue.body() == null ? "" : issue.body(),
                labels.toArray(String[]::new));
        return ResponseEntity.accepted().body(Map.of(
                "repo", props.repo(), "issue", issue.number(), "labels", labels,
                "note", "The poller picks it up within factory.poller.interval"));
    }

    @GetMapping("/pull-requests")
    public List<FakeGitHubClient.OpenedPr> pullRequests() {
        return github.openedPullRequests();
    }
}
