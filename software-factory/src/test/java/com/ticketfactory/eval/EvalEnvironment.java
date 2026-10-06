package com.ticketfactory.eval;

import com.ticketfactory.integration.agent.AgentProperties;
import com.ticketfactory.integration.agent.AgentTestEnvironment;
import com.ticketfactory.integration.agent.ClaudeCodeAgentRunner;
import com.ticketfactory.integration.checks.ChecksProperties;
import com.ticketfactory.integration.checks.SandboxChecksRunner;
import com.ticketfactory.integration.docker.DockerSandboxFixture;
import com.ticketfactory.integration.docker.DockerSandboxRunner;
import com.ticketfactory.integration.docker.HostGit;
import com.ticketfactory.integration.docker.ModelApiProxy;
import java.io.IOException;
import java.time.Duration;

/**
 * The live evaluation's setup: the real model API through the real proxy, with the credential from the environment
 * ({@code ANTHROPIC_API_KEY} or {@code CLAUDE_CODE_OAUTH_TOKEN}). Only GitHub is a fake (a local bare repo).
 */
final class EvalEnvironment implements AutoCloseable {

    final DockerSandboxFixture fx;
    final ModelApiProxy proxy;
    final DockerSandboxRunner runner;
    final AgentProperties agentProps;

    static boolean credentialAvailable() {
        return !env("ANTHROPIC_API_KEY").isBlank() || !env("CLAUDE_CODE_OAUTH_TOKEN").isBlank();
    }

    EvalEnvironment() throws IOException {
        fx = new DockerSandboxFixture();
        AgentTestEnvironment.ensureImage(fx.docker);
        String apiKey = env("ANTHROPIC_API_KEY");
        // Override only to dry-run the harness against a stand-in API.
        String upstream = env("FACTORY_EVAL_UPSTREAM_URL").isBlank() ? "https://api.anthropic.com"
                : env("FACTORY_EVAL_UPSTREAM_URL");
        proxy = apiKey.isBlank()
                ? new ModelApiProxy(fx.docker, "nginx:1.27-alpine", "factory-egress-eval", upstream,
                        ModelApiProxy.Mode.OAUTH_TOKEN, env("CLAUDE_CODE_OAUTH_TOKEN"))
                : new ModelApiProxy(fx.docker, "nginx:1.27-alpine", "factory-egress-eval", upstream,
                        ModelApiProxy.Mode.API_KEY, apiKey);
        runner = fx.runnerWithProxy(AgentTestEnvironment.IMAGE, proxy);
        agentProps = new AgentProperties("", "", "claude", env("FACTORY_AGENT_MODEL"), "Bash,Read,Edit,Write",
                "unused", "unused", "unused", Duration.ofMinutes(10));
    }

    ClaudeCodeAgentRunner agent() {
        return new ClaudeCodeAgentRunner(runner, agentProps, "main");
    }

    SandboxChecksRunner checks() {
        HostGit git = new HostGit(fx.root.resolve("checks-host"), "file://" + fx.root.resolve("remotes") + "/{repo}.git",
                null, Duration.ofMinutes(2));
        return new SandboxChecksRunner(runner, git,
                new ChecksProperties(".factory.yml", "", Duration.ofMinutes(5), Duration.ofMinutes(10)), "main");
    }

    String modelLabel() {
        return agentProps.model().isBlank() ? "CLI default" : agentProps.model();
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v.strip();
    }

    @Override
    public void close() throws IOException {
        runner.list().stream().filter(s -> s.ticketId() >= fx.idBase && s.ticketId() < fx.idBase + 10_000)
                .forEach(s -> runner.destroy(s.id()));
        fx.close();
    }
}
