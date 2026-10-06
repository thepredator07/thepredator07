package com.ticketfactory.integration.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.ticketfactory.integration.TicketContext;
import com.ticketfactory.integration.docker.DockerSandboxFixture;
import com.ticketfactory.integration.docker.DockerSandboxRunner;
import com.ticketfactory.integration.docker.ModelApiProxy;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The sandbox's only way out, on real Docker: what gets through the proxy, what it adds, and what can't get out. */
class ModelApiProxyTest {

    private AgentTestEnvironment env;
    private TicketContext ticket;
    private String sbx;

    @BeforeEach
    void start() throws Exception {
        env = new AgentTestEnvironment(ModelApiProxy.Mode.API_KEY);
        long id = env.fx.id(1);
        ticket = new TicketContext(id, DockerSandboxFixture.REPO, 1, "Proxy", "", "factory/" + id);
        sbx = env.runner.prepare(ticket).id();
    }

    @AfterEach
    void stop() throws Exception {
        env.close();
    }

    /** curl from inside the sandbox, as the agent's code would. Returns "<http status>" or curl's error. */
    private String curl(String url, String... headers) {
        StringBuilder cmd = new StringBuilder("curl -sS -m 5 -o /dev/null -w '%{http_code}' -X POST");
        for (String h : headers) {
            cmd.append(" -H '").append(h).append("'");
        }
        cmd.append(" -H 'content-type: application/json' -d '{\"model\":\"m\",\"max_tokens\":5,\"messages\":[]}' ")
                .append(url).append(" 2>&1");
        return env.runner.exec(sbx, Duration.ofSeconds(20), "sh", "-c", cmd.toString()).output().strip();
    }

    private JsonNode lastRequest() {
        var all = env.requests();
        assertThat(all).isNotEmpty();
        return all.getLast();
    }

    @Test
    void modelCallsGoThroughWithTheRealKeyInPlaceOfThePlaceholder() {
        env.proxy.start(ticket.ticketId());

        String status = curl(ModelApiProxy.sandboxBaseUrl() + "/v1/messages",
                "x-api-key: " + ModelApiProxy.PLACEHOLDER_API_KEY, "authorization: Bearer something-else");

        assertThat(status).isEqualTo("200");
        JsonNode headers = lastRequest().get("headers");
        assertThat(header(headers, "x-api-key")).isEqualTo(AgentTestEnvironment.REAL_KEY);
        assertThat(header(headers, "authorization")).as("the sandbox's own Authorization is dropped").isNull();
        assertThat(header(headers, "host")).isEqualTo("fake-anthropic:8080");
    }

    @Test
    void inOauthModeTheProxySendsTheTokenAsBearer() throws Exception {
        env.close();
        env = new AgentTestEnvironment(ModelApiProxy.Mode.OAUTH_TOKEN);
        long id = env.fx.id(2);
        ticket = new TicketContext(id, DockerSandboxFixture.REPO, 2, "Proxy", "", "factory/" + id);
        sbx = env.runner.prepare(ticket).id();
        env.proxy.start(id);

        assertThat(curl(ModelApiProxy.sandboxBaseUrl() + "/v1/messages",
                "authorization: Bearer " + ModelApiProxy.PLACEHOLDER_OAUTH_TOKEN, "x-api-key: x")).isEqualTo("200");

        JsonNode headers = lastRequest().get("headers");
        assertThat(header(headers, "authorization")).isEqualTo("Bearer " + AgentTestEnvironment.REAL_OAUTH);
        assertThat(header(headers, "x-api-key")).isNull();
    }

    @Test
    void onlyTheModelApiIsForwarded() {
        env.proxy.start(ticket.ticketId());
        int before = env.requests().size();

        assertThat(curl(ModelApiProxy.sandboxBaseUrl() + "/admin")).isEqualTo("403");
        assertThat(curl(ModelApiProxy.sandboxBaseUrl() + "/")).isEqualTo("403");
        assertThat(env.requests()).as("nothing reached the upstream").hasSize(before);
    }

    @Test
    void theSandboxCannotReachTheUpstreamDirectlyOrTheInternet() {
        env.proxy.start(ticket.ticketId());
        String upstreamIp = env.docker.inspectContainerCmd(env.apiContainer).exec().getNetworkSettings()
                .getNetworks().get(env.egress).getIpAddress();

        assertThat(curl("http://fake-anthropic:8080/v1/messages")).contains("Could not resolve host");
        assertThat(curl("http://" + upstreamIp + ":8080/v1/messages")).contains("Failed to connect").endsWith("000");
        assertThat(curl("https://api.anthropic.com/v1/messages")).contains("Could not resolve host");
        assertThat(curl("http://1.1.1.1/")).contains("Failed to connect").endsWith("000");
    }

    @Test
    void nothingIsReachableWhileTheProxyIsStopped() {
        env.proxy.start(ticket.ticketId());
        assertThat(curl(ModelApiProxy.sandboxBaseUrl() + "/v1/messages")).isEqualTo("200");

        env.proxy.stop(ticket.ticketId());

        assertThat(env.proxy.isRunning(ticket.ticketId())).isFalse();
        assertThat(curl(ModelApiProxy.sandboxBaseUrl() + "/v1/messages")).contains("Could not resolve host");
    }

    @Test
    void theSandboxIsOnlyOnItsOwnInternalNetwork() {
        var info = env.runner.inspect(sbx);
        assertThat(info.getNetworkSettings().getNetworks()).containsOnlyKeys(ModelApiProxy.networkName(ticket.ticketId()));
        var net = env.docker.inspectNetworkCmd().withNetworkId(ModelApiProxy.networkName(ticket.ticketId())).exec();
        assertThat(net.getInternal()).isTrue();
    }

    @Test
    void destroyingTheSandboxRemovesItsProxyAndNetwork() {
        env.runner.destroy(sbx);

        assertThat(env.runner.inspect(ModelApiProxy.containerName(ticket.ticketId()))).isNull();
        assertThat(env.proxy.ticketIds()).doesNotContain(ticket.ticketId());
        assertThat(env.runner.list()).extracting(s -> s.ticketId()).doesNotContain(ticket.ticketId());
    }

    @Test
    void aProxyLeftWithoutItsSandboxIsListedSoTheJanitorRemovesIt() {
        // The app died after removing the sandbox container but before removing the proxy and network.
        env.docker.removeContainerCmd(sbx).withForce(true).exec();

        assertThat(env.runner.list()).extracting(s -> s.id()).contains(DockerSandboxRunner.containerName(ticket.ticketId()));
        env.runner.destroy(DockerSandboxRunner.containerName(ticket.ticketId()));
        assertThat(env.proxy.ticketIds()).doesNotContain(ticket.ticketId());
    }

    @Test
    void checksNeverRunWithAWayOutEvenIfTheProxyWasLeftRunning() {
        env.fx.commitToMain(java.util.Map.of(".factory.yml",
                "checks:\n  command: curl -sS -m 5 -X POST http://model-proxy:8080/v1/messages\n"), "phone home");
        env.proxy.start(ticket.ticketId()); // e.g. the worker died during an agent run
        var checks = new com.ticketfactory.integration.checks.SandboxChecksRunner(env.runner,
                new com.ticketfactory.integration.docker.HostGit(env.fx.root.resolve("checks-host"),
                        "file://" + env.fx.root.resolve("remotes") + "/{repo}.git", null, Duration.ofMinutes(1)),
                new com.ticketfactory.integration.checks.ChecksProperties(".factory.yml", "", Duration.ofMinutes(1),
                        Duration.ofMinutes(5)), "main");
        int before = env.requests().size();

        var result = checks.run(ticket, sbx);

        assertThat(result.passed()).isFalse();
        assertThat(result.output()).contains("Could not resolve host");
        assertThat(env.requests()).hasSize(before);
        assertThat(env.proxy.isRunning(ticket.ticketId())).isFalse();
    }

    @Test
    void credentialsThatCouldChangeTheProxyConfigAreRejected() {
        for (String bad : new String[] {"", "a b", "key\";\nproxy_pass http://evil", "x{y}"}) {
            assertThatThrownBy(() -> new ModelApiProxy(env.docker, "nginx", "n", "https://api.anthropic.com",
                    ModelApiProxy.Mode.API_KEY, bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ModelApiProxy(env.docker, "nginx", "n", "ftp://x", ModelApiProxy.Mode.API_KEY,
                "k")).isInstanceOf(IllegalArgumentException.class);
    }

    private static String header(JsonNode headers, String name) {
        var it = headers.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue().asText();
            }
        }
        return null;
    }
}
