package com.ticketfactory.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.fake.FakeProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BranchPolicyTest {

    @Test
    void branchNameIsFactoryPrefixPlusTicketId() {
        assertThat(BranchPolicy.branchFor(42)).isEqualTo("factory/42");
        assertThatCode(() -> BranchPolicy.validatePullRequest("factory/42", "main")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"main", "master", "feature/x", "factory/", "factory", ""})
    void rejectsAnythingThatIsNotAFactoryBranch(String head) {
        assertThatThrownBy(() -> BranchPolicy.validatePullRequest(head, "main"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsHeadEqualToBase() {
        assertThatThrownBy(() -> BranchPolicy.validatePullRequest("factory/1", "factory/1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fakeGitHubEnforcesThePolicy() {
        FakeGitHubClient gh = new FakeGitHubClient(new FakeProperties.GitHub("succeed", 1, true));
        assertThatThrownBy(() -> gh.openPullRequest(
                new GitHubClient.PullRequestRequest("acme/app", 1, "main", "main", "t", "b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(gh.openedPullRequests()).isEmpty();
    }
}
