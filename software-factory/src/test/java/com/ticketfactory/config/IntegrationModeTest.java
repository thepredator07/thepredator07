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
    void realModeWithoutAModelCredentialFailsFastWithAClearMessage() {
        runner.withPropertyValues("factory.integrations=real")
                .withBean(NeedsGitHub.class)
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    Throwable cause = NestedExceptionUtils.getMostSpecificCause(ctx.getStartupFailure());
                    assertThat(cause).hasMessageContaining("needs a model API credential")
                            .hasMessageContaining("ANTHROPIC_API_KEY").hasMessageContaining("CLAUDE_CODE_OAUTH_TOKEN");
                    assertThat(cause.getMessage()).doesNotContain("No qualifying bean");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({com.ticketfactory.integration.github.GitHubProperties.class,
            com.ticketfactory.integration.docker.SandboxProperties.class,
            com.ticketfactory.integration.checks.ChecksProperties.class,
            com.ticketfactory.integration.agent.AgentProperties.class})
    static class RealProps {
        @org.springframework.context.annotation.Bean
        java.time.Clock clock() {
            return java.time.Clock.systemUTC();
        }
    }

    @Test
    void realModeWithACredentialWiresTheRealAgentChecksAndSandboxBehindTheProxy() {
        runner.withUserConfiguration(RealProps.class)
                .withPropertyValues("factory.integrations=real", "factory.github.token=ghp_test",
                        "factory.agent.oauth-token=sk-ant-oat01-test-token")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(AgentRunner.class))
                            .isInstanceOf(com.ticketfactory.integration.agent.ClaudeCodeAgentRunner.class);
                    assertThat(ctx.getBean(ChecksRunner.class))
                            .isInstanceOf(com.ticketfactory.integration.checks.SandboxChecksRunner.class);
                    var sandbox = ctx.getBean(com.ticketfactory.integration.docker.DockerSandboxRunner.class);
                    assertThat(sandbox.proxy()).isNotNull();
                    assertThat(sandbox.proxy().mode())
                            .isEqualTo(com.ticketfactory.integration.docker.ModelApiProxy.Mode.OAUTH_TOKEN);
                    assertThat(ctx).doesNotHaveBean(FakeGitHubClient.class);
                });
    }

    @Test
    void anApiKeyIsPreferredOverASubscriptionToken() {
        runner.withUserConfiguration(RealProps.class)
                .withPropertyValues("factory.integrations=real", "factory.github.token=ghp_test",
                        "factory.agent.oauth-token=sk-ant-oat01-test", "factory.agent.api-key=sk-ant-api03-test")
                .run(ctx -> assertThat(ctx.getBean(com.ticketfactory.integration.docker.ModelApiProxy.class).mode())
                        .isEqualTo(com.ticketfactory.integration.docker.ModelApiProxy.Mode.API_KEY));
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
