package com.ticketfactory.fake;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires every integration as a fake. Active when {@code factory.integrations=fake} (the default). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "factory.integrations", havingValue = "fake", matchIfMissing = true)
public class FakeIntegrationsConfig {

    @Bean
    FakeGitHubClient fakeGitHubClient(FakeProperties props) {
        return new FakeGitHubClient(props.github());
    }

    @Bean
    FakeSandboxRunner fakeSandboxRunner(FakeProperties props) {
        return new FakeSandboxRunner(props.sandbox().behavior());
    }

    @Bean
    FakeAgentRunner fakeAgentRunner(FakeProperties props) {
        return new FakeAgentRunner(props.agent());
    }

    @Bean
    FakeChecksRunner fakeChecksRunner(FakeProperties props) {
        return new FakeChecksRunner(props.checks().behavior());
    }
}
