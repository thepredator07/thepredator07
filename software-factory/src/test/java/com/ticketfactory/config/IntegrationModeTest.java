package com.ticketfactory.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.ticketfactory.FactoryProperties;
import com.ticketfactory.fake.FakeApprovalController;
import com.ticketfactory.fake.FakeGitHubClient;
import com.ticketfactory.fake.FakeGitHubController;
import com.ticketfactory.fake.FakeIntegrationsConfig;
import com.ticketfactory.fake.FakeProperties;
import com.ticketfactory.integration.AgentRunner;
import com.ticketfactory.integration.ChecksRunner;
import com.ticketfactory.integration.GitHubClient;
import com.ticketfactory.integration.SandboxRunner;
import com.ticketfactory.integration.real.RealIntegrationsConfig;
import com.ticketfactory.queue.JobQueue;
import com.ticketfactory.ticket.TicketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** factory.integrations decides which implementations exist; fake-only web endpoints follow the same switch. */
class IntegrationModeTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({FactoryProperties.class, FakeProperties.class})
    static class Props {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Props.class, FakeIntegrationsConfig.class, RealIntegrationsConfig.class,
                    FakeApprovalController.class, FakeGitHubController.class)
            .withBean(TicketRepository.class, () -> mock(TicketRepository.class))
            .withBean(JobQueue.class, () -> mock(JobQueue.class));

    @Test
    void fakeIsTheDefaultAndWiresAllFourFakesPlusFakeEndpoints() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(GitHubClient.class).hasSingleBean(SandboxRunner.class)
                    .hasSingleBean(AgentRunner.class).hasSingleBean(ChecksRunner.class);
            assertThat(ctx.getBean(GitHubClient.class)).isInstanceOf(FakeGitHubClient.class);
            assertThat(ctx).hasSingleBean(FakeApprovalController.class).hasSingleBean(FakeGitHubController.class);
            assertThat(ctx.getBean(FactoryProperties.class).fakeMode()).isTrue();
        });
    }

    @Test
    void explicitFakeModeIsCaseInsensitive() {
        runner.withPropertyValues("factory.integrations=FAKE")
                .run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(FakeGitHubClient.class));
    }

    /** Stands in for GitHubPoller: anything that needs a GitHubClient. */
    record NeedsGitHub(GitHubClient github) {
    }

    @Test
    void realModeFailsFastWithAClearMessageBeforeAnythingAsksForAMissingClient() {
        runner.withPropertyValues("factory.integrations=real")
                .withBean(NeedsGitHub.class)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    Throwable cause = NestedExceptionUtils.getMostSpecificCause(ctx.getStartupFailure());
                    assertThat(cause).hasMessageContaining("factory.integrations=real is not implemented yet");
                    assertThat(cause.getMessage()).doesNotContain("No qualifying bean");
                });
    }

    @Test
    void realModeRegistersNoFakesAndNoFakeEndpoints() {
        new ApplicationContextRunner()
                .withUserConfiguration(Props.class, FakeIntegrationsConfig.class, FakeApprovalController.class,
                        FakeGitHubController.class)
                .withPropertyValues("factory.integrations=real")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(FakeGitHubClient.class)
                            .doesNotHaveBean(SandboxRunner.class)
                            .doesNotHaveBean(FakeApprovalController.class)
                            .doesNotHaveBean(FakeGitHubController.class);
                    assertThat(ctx.getBean(FactoryProperties.class).fakeMode()).isFalse();
                });
    }
}
