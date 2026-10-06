package com.ticketfactory.contract;

import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.fake.FakeProperties;
import com.ticketfactory.integration.GitHubClient;
import java.util.concurrent.atomic.AtomicInteger;

class FakeGitHubClientContractTest extends GitHubClientContract {

    private final FakeGitHubClient fake = new FakeGitHubClient(new FakeProperties.GitHub("succeed", 1, false));
    private final AtomicInteger numbers = new AtomicInteger();

    @Override
    protected GitHubClient client() {
        return fake;
    }

    @Override
    protected String repo() {
        return "example-org/contract";
    }

    @Override
    protected int openIssue(String title, String body, String... labels) {
        int n = numbers.incrementAndGet();
        fake.addIssue(repo(), n, title, body, labels);
        return n;
    }

    @Override
    protected void approve(int prNumber) {
        fake.setPullRequestStatus(prNumber, GitHubClient.PrStatus.APPROVED);
    }
}
