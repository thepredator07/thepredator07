package com.ticketfactory.integration.real;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.integration.ChecksRunner;
import com.ticketfactory.integration.GitHubClient;
import com.ticketfactory.integration.checks.ChecksProperties;
import com.ticketfactory.integration.checks.SandboxChecksRunner;
import com.ticketfactory.integration.docker.DockerSandboxRunner;
import com.ticketfactory.integration.docker.HostGit;
import com.ticketfactory.integration.docker.SandboxProperties;
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
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for {@code factory.integrations=real}.
 *
 * <p>Done: {@link GitHubRestClient} (M2), {@link DockerSandboxRunner} (M3), {@link SandboxChecksRunner} (M4).
 * Still missing: AgentRunner (M5).
 * Until all four exist, startup fails with a clear message (see {@link #notCompleteYet()}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "factory.integrations", havingValue = "real")
public class RealIntegrationsConfig {

    /** Integrations without a real implementation yet. Remove the entry when M5 lands. */
    static final List<String> MISSING = List.of("AgentRunner (M5)");

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
    GitHubAuth gitHubAuth(GitHubProperties props, Clock clock) {
        return createAuth(props, httpClient(props), clock);
    }

    @Bean
    GitHubClient gitHubClient(GitHubProperties props, GitHubAuth auth, Clock clock) {
        return new GitHubRestClient(new GitHubHttp(props.apiUrl(), auth, httpClient(props), props.requestTimeout(),
                clock), props, clock);
    }

    @Bean(destroyMethod = "close")
    DockerClient dockerClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient http = new ZerodepDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost()).sslConfig(config.getSSLConfig())
                .connectionTimeout(Duration.ofSeconds(10)).responseTimeout(Duration.ofMinutes(10)).build();
        return DockerClientImpl.getInstance(config, http);
    }

    @Bean
    HostGit hostGit(SandboxProperties sandbox, GitHubAuth auth) {
        // The host fetches and pushes with the same identity the client uses; the sandbox itself never sees it.
        return new HostGit(sandbox.hostWorkDir(), sandbox.cloneUrlTemplate(),
                () -> auth.authorizationHeader().replaceFirst("^Bearer ", ""), sandbox.gitTimeout());
    }

    @Bean
    DockerSandboxRunner sandboxRunner(DockerClient docker, SandboxProperties sandbox, HostGit git,
                                      FactoryProperties factory) {
        return new DockerSandboxRunner(docker, git, sandbox, factory.baseBranch());
    }

    @Bean
    ChecksRunner checksRunner(DockerSandboxRunner sandbox, HostGit git, ChecksProperties checks,
                              FactoryProperties factory) {
        return new SandboxChecksRunner(sandbox, git, checks, factory.baseBranch());
    }

    /** Builds a client with its own auth from configuration (tests and tools). */
    public static GitHubRestClient createGitHubClient(GitHubProperties props, Clock clock) {
        HttpClient http = httpClient(props);
        return new GitHubRestClient(new GitHubHttp(props.apiUrl(), createAuth(props, http, clock), http,
                props.requestTimeout(), clock), props, clock);
    }

    private static HttpClient httpClient(GitHubProperties props) {
        return HttpClient.newBuilder().connectTimeout(props.requestTimeout()).build();
    }

    /** GitHub App if any App setting is present, otherwise GITHUB_TOKEN. */
    static GitHubAuth createAuth(GitHubProperties props, HttpClient http, Clock clock) {
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
        return auth;
    }
}
