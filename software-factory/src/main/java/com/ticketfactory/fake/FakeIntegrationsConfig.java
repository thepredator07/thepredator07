package com.ticketfactory.fake;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 1 wiring: every integration is a fake.
 * TODO(phase-2): add a {@code factory.integrations=real} switch that registers the implementations in
 * {@code com.ticketfactory.integration.phase2} instead.
 */
@Configuration(proxyBeanMethods = false)
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
