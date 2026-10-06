package com.ticketfactory.integration.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.ticketfactory.integration.docker.DockerSandboxFixture;
import com.ticketfactory.integration.docker.DockerSandboxRunner;
import com.ticketfactory.integration.docker.ModelApiProxy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Everything the agent tests need, all real except the model: the sandbox image with the real Claude Code CLI (built
 * from {@code sandbox/Dockerfile} if missing), the per-ticket model proxy, and a scripted fake Anthropic API
 * ({@code fake_anthropic.py}) on a private egress network. No credential and no cost.
 */
public final class AgentTestEnvironment implements AutoCloseable {

    public static final String IMAGE = "factory-sandbox:latest";
    /** The "real" credential the proxy injects. Tests check it reaches the API and never the sandbox. */
    public static final String REAL_KEY = "sk-ant-api03-REAL-KEY-ONLY-ON-THE-HOST-123";
    public static final String REAL_OAUTH = "sk-ant-oat01-REAL-TOKEN-ONLY-ON-THE-HOST-456";

    public final DockerSandboxFixture fx;
    public final DockerClient docker;
    public final String egress = "factory-egress-test-" + ThreadLocalRandom.current().nextInt(1_000_000);
    public final String apiContainer = egress + "-api";
    public final ModelApiProxy proxy;
    public final DockerSandboxRunner runner;

    public AgentTestEnvironment(ModelApiProxy.Mode mode) throws IOException {
        fx = new DockerSandboxFixture();
        docker = fx.docker;
        ensureImage(docker);
        startFakeApi();
        proxy = new ModelApiProxy(docker, "nginx:1.27-alpine", egress, "http://fake-anthropic:8080", mode,
                mode == ModelApiProxy.Mode.API_KEY ? REAL_KEY : REAL_OAUTH);
        runner = fx.runnerWithProxy(IMAGE, proxy);
    }

    public ClaudeCodeAgentRunner agent() {
        return new ClaudeCodeAgentRunner(runner, new AgentProperties("", "", "claude", "", "Bash,Read,Edit,Write",
                "unused", "unused", "unused", Duration.ofMinutes(5)), "main");
    }

    /** Builds the sandbox image from the repo's Dockerfile once per machine (needs network for the download). */
    public static synchronized void ensureImage(DockerClient docker) {
        try {
            docker.inspectImageCmd(IMAGE).exec();
            return;
        } catch (NotFoundException e) {
            // build it
        }
        var build = docker.buildImageCmd(Path.of("sandbox").toAbsolutePath().toFile())
                .withTags(java.util.Set.of(IMAGE)).withNetworkMode("host").withPull(false);
        String proxyEnv = System.getenv("HTTPS_PROXY"); // only where the host itself needs a proxy
        if (proxyEnv != null && !proxyEnv.isBlank()) {
            build = build.withBuildArg("HTTPS_PROXY", proxyEnv);
        }
        build.exec(new BuildImageResultCallback()).awaitImageId(10, TimeUnit.MINUTES);
    }

    private void startFakeApi() throws IOException {
        String src;
        try (InputStream in = getClass().getResourceAsStream("/agent/fake_anthropic.py")) {
            src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        docker.createNetworkCmd().withName(egress).withLabels(Map.of("factory.test", "true")).exec();
        docker.createContainerCmd(DockerSandboxFixture.IMAGE).withName(apiContainer)
                .withEnv("FAKE_API_SRC=" + src)
                .withCmd("sh", "-c", "printf '%s' \"$FAKE_API_SRC\" > /tmp/f.py && touch /tmp/req.log"
                        + " && exec python3 /tmp/f.py 8080 /tmp/req.log")
                .withHostConfig(com.github.dockerjava.api.model.HostConfig.newHostConfig().withNetworkMode(egress))
                .withAliases("fake-anthropic")
                .exec();
        docker.startContainerCmd(apiContainer).exec();
    }

    /** Every request the fake API received, oldest first. */
    public List<JsonNode> requests() {
        String log = execIn(apiContainer, "cat", "/tmp/req.log");
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> out = new ArrayList<>();
        for (String line : log.split("\n")) {
            if (!line.isBlank()) {
                try {
                    out.add(json.readTree(line));
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return out;
    }

    /** Runs a command in any container (not as the sandbox user), returning stdout and stderr. */
    public String execIn(String container, String... cmd) {
        var exec = docker.execCreateCmd(container).withCmd(cmd).withAttachStdout(true).withAttachStderr(true).exec();
        var out = new java.io.ByteArrayOutputStream();
        try {
            docker.execStartCmd(exec.getId()).exec(new com.github.dockerjava.api.async.ResultCallback.Adapter<
                    com.github.dockerjava.api.model.Frame>() {
                @Override
                public void onNext(com.github.dockerjava.api.model.Frame f) {
                    out.writeBytes(f.getPayload());
                }
            }).awaitCompletion(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
        // This environment's sandboxes, proxies and networks (the proxy-aware runner removes all three).
        runner.list().stream().filter(sb -> sb.ticketId() >= fx.idBase && sb.ticketId() < fx.idBase + 10_000)
                .forEach(sb -> runner.destroy(sb.id()));
        fx.close();
        try {
            docker.removeContainerCmd(apiContainer).withForce(true).exec();
        } catch (NotFoundException ignored) {
            // gone
        }
        try {
            docker.removeNetworkCmd(egress).exec();
        } catch (RuntimeException ignored) {
            // still in use by a leftover proxy of another test; harmless
        }
    }
}
