package com.ticketfactory.integration.real;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.transport.DockerHttpClient;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import com.ticketfactory.FactoryProperties;
import com.ticketfactory.integration.AgentRunner;
import com.ticketfactory.integration.ChecksRunner;
import com.ticketfactory.integration.agent.AgentProperties;
import com.ticketfactory.integration.agent.ClaudeCodeAgentRunner;
import com.ticketfactory.integration.GitHubClient;
import com.ticketfactory.integration.checks.ChecksProperties;
import com.ticketfactory.integration.checks.SandboxChecksRunner;
import com.ticketfactory.integration.docker.DockerSandboxRunner;
import com.ticketfactory.integration.docker.HostGit;
import com.ticketfactory.integration.docker.ModelApiProxy;
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
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContextException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wiring for {@code factory.integrations=real}.
 *
 * <p>{@link GitHubRestClient} (M2), {@link DockerSandboxRunner} (M3), {@link SandboxChecksRunner} (M4) and
 * {@link ClaudeCodeAgentRunner} with its {@link ModelApiProxy} (M5). Startup fails with a clear message when the model
 * API credential is missing (see {@link #modelCredentialRequired()}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "factory.integrations", havingValue = "real")
public class RealIntegrationsConfig {

    /**
     * Fails startup with a clear message when there is no model API credential. A bean-factory post-processor runs
     * before any regular bean is created, so this fires before anything fails with an obscure error.
     */
    @Bean
    static BeanFactoryPostProcessor modelCredentialRequired() {
        return beanFactory -> {
            Environment env = beanFactory.getBean(Environment.class);
            if (env.getProperty("factory.agent.api-key", "").isBlank()
                    && env.getProperty("factory.agent.oauth-token", "").isBlank()) {
                throw new ApplicationContextException("factory.integrations=real needs a model API credential: set"
                        + " ANTHROPIC_API_KEY or CLAUDE_CODE_OAUTH_TOKEN (from `claude setup-token`).");
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
    ModelApiProxy modelApiProxy(DockerClient docker, AgentProperties agent) {
        // An API key is preferred when both are set: it is the credential meant for automation.
        return agent.apiKey().isBlank()
                ? new ModelApiProxy(docker, agent.proxyImage(), agent.egressNetwork(), agent.upstreamUrl(),
                        ModelApiProxy.Mode.OAUTH_TOKEN, agent.oauthToken().strip())
                : new ModelApiProxy(docker, agent.proxyImage(), agent.egressNetwork(), agent.upstreamUrl(),
                        ModelApiProxy.Mode.API_KEY, agent.apiKey().strip());
    }

    @Bean
    DockerSandboxRunner sandboxRunner(DockerClient docker, SandboxProperties sandbox, HostGit git,
                                      FactoryProperties factory, ModelApiProxy proxy) {
        return new DockerSandboxRunner(docker, git, sandbox, factory.baseBranch(), proxy);
    }

    @Bean
    AgentRunner agentRunner(DockerSandboxRunner sandbox, AgentProperties agent, FactoryProperties factory) {
        return new ClaudeCodeAgentRunner(sandbox, agent, factory.baseBranch());
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
