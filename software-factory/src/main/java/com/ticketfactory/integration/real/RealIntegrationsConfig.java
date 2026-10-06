package com.ticketfactory.integration.real;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for {@code factory.integrations=real}.
 * TODO(phase-2): register GitHubRestClient (M2), DockerSandboxRunner (M3), SandboxChecksRunner (M4) and
 * ClaudeCodeAgentRunner (M5) here, and delete {@link #notImplementedYet()}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "factory.integrations", havingValue = "real")
public class RealIntegrationsConfig {

    /**
     * Fails startup with a clear message. A bean-factory post-processor runs before any regular bean is created, so
     * this fires before something like GitHubPoller can fail with an obscure "no bean of type GitHubClient".
     */
    @Bean
    static BeanFactoryPostProcessor notImplementedYet() {
        return beanFactory -> {
            throw new ApplicationContextException("factory.integrations=real is not implemented yet (Phase 2, "
                    + "milestones M2-M5 in docs/PHASE2-PLAN.md). Use factory.integrations=fake.");
        };
    }
}
