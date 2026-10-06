package com.ticketfactory.integration.real;

import com.ticketfactory.integration.GitHubClient;
import com.ticketfactory.integration.github.GitHubAppAuth;
import com.ticketfactory.integration.github.GitHubAuth;
import com.ticketfactory.integration.github.GitHubHttp;
import com.ticketfactory.integration.github.GitHubProperties;
import com.ticketfactory.integration.github.GitHubRestClient;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for {@code factory.integrations=real}.
 *
 * <p>Done: {@link GitHubRestClient} (M2). Still missing: SandboxRunner (M3), ChecksRunner (M4), AgentRunner (M5).
 * Until all four exist, startup fails with a clear message (see {@link #notCompleteYet()}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "factory.integrations", havingValue = "real")
public class RealIntegrationsConfig {

    /** Integrations without a real implementation yet. Remove entries as M3-M5 land. */
    static final List<String> MISSING = List.of("SandboxRunner (M3)", "ChecksRunner (M4)", "AgentRunner (M5)");

    /**
     * Fails startup with a clear message. A bean-factory post-processor runs before any regular bean is created, so
     * this fires before something like GitHubPoller can fail with an obscure "no bean of type ...".
     */
    @Bean
    static BeanFactoryPostProcessor notCompleteYet() {
        return beanFactory -> {
            if (!MISSING.isEmpty()) {
                throw new ApplicationContextException("factory.integrations=real is not implemented yet: missing "
                        + String.join(", ", MISSING) + " (see docs/PHASE2-PLAN.md). Use factory.integrations=fake.");
            }
        };
    }

    @Bean
    GitHubClient gitHubClient(GitHubProperties props, Clock clock) {
        return createGitHubClient(props, clock);
    }

    /** Builds the client from configuration: GitHub App if any App setting is present, otherwise GITHUB_TOKEN. */
    public static GitHubRestClient createGitHubClient(GitHubProperties props, Clock clock) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(props.requestTimeout()).build();
        GitHubAuth auth;
        if (props.usesApp()) {
            String pem;
            try {
                pem = Files.readString(Path.of(props.appPrivateKeyPath()));
            } catch (IOException | NullPointerException e) {
                throw new IllegalStateException("Cannot read GITHUB_APP_PRIVATE_KEY_PATH: " + props.appPrivateKeyPath(), e);
            }
            auth = new GitHubAppAuth(props.apiUrl(), props.appId(), props.appInstallationId(), pem, http,
                    props.requestTimeout(), clock);
        } else {
            auth = GitHubAuth.token(props.token());
        }
        return new GitHubRestClient(new GitHubHttp(props.apiUrl(), auth, http, props.requestTimeout(), clock), props,
                clock);
    }
}
