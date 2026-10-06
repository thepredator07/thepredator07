package com.ticketfactory.integration.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import com.ticketfactory.integration.StepFailedException;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only way out of a sandbox: a small nginx reverse proxy per ticket that forwards model API calls and adds the
 * real credential on the way.
 *
 * <ul>
 *   <li>Each ticket gets an <b>internal</b> Docker network {@code factory-net-<id>} (no route anywhere). Its sandbox
 *       is attached only to that network; the proxy {@code factory-proxy-<id>} is attached to it (as
 *       {@code model-proxy}) and to the egress network.</li>
 *   <li>The proxy forwards {@code /v1/*} to the model API only and answers everything else with 403. It replaces the
 *       placeholder credential the sandbox sends with the real one, so <b>the sandbox never holds the token</b>: the
 *       agent, the code it writes and the checks cannot read it, print it or commit it.</li>
 *   <li>The proxy runs only while the agent runs ({@link #start}/{@link #stop}). During checks nothing is reachable,
 *       so code from the repo or the agent cannot spend on the factory's credential.</li>
 * </ul>
 */
public class ModelApiProxy {

    public static final String ALIAS = "model-proxy";
    public static final int PORT = 8080;
    /** What the sandbox puts in its credential variable. The proxy swaps it for the real one. */
    public static final String PLACEHOLDER_API_KEY = "sk-ant-api03-factory-placeholder-not-a-key";
    public static final String PLACEHOLDER_OAUTH_TOKEN = "sk-ant-oat01-factory-placeholder-not-a-token";
    static final String LABEL_ROLE = "factory.role";
    private static final Logger log = LoggerFactory.getLogger(ModelApiProxy.class);

    /** Which credential the proxy injects, and so which placeholder variable the sandbox must set. */
    public enum Mode {
        API_KEY("ANTHROPIC_API_KEY", PLACEHOLDER_API_KEY),
        OAUTH_TOKEN("CLAUDE_CODE_OAUTH_TOKEN", PLACEHOLDER_OAUTH_TOKEN);

        public final String sandboxVariable;
        public final String placeholder;

        Mode(String sandboxVariable, String placeholder) {
            this.sandboxVariable = sandboxVariable;
            this.placeholder = placeholder;
        }
    }

    private final DockerClient docker;
    private final String image;
    private final String egressNetwork;
    private final URI upstream;
    private final Mode mode;
    private final String credential;

    public ModelApiProxy(DockerClient docker, String image, String egressNetwork, String upstreamUrl, Mode mode,
                         String credential) {
        if (credential == null || !credential.matches("[A-Za-z0-9._\\-]+")) {
            // Goes into the nginx config; anything else could change its meaning.
            throw new IllegalArgumentException("model API credential is missing or has unexpected characters");
        }
        URI uri = URI.create(upstreamUrl);
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || !uri.getHost().matches("[A-Za-z0-9.\\-]+")) {
            throw new IllegalArgumentException("bad model API upstream URL: " + upstreamUrl);
        }
        this.docker = docker;
        this.image = image;
        this.egressNetwork = egressNetwork;
        this.upstream = uri;
        this.mode = mode;
        this.credential = credential;
    }

    public Mode mode() {
        return mode;
    }

    public static String networkName(long ticketId) {
        return "factory-net-" + ticketId;
    }

    public static String containerName(long ticketId) {
        return "factory-proxy-" + ticketId;
    }

    /** The base URL Claude Code inside the sandbox uses ({@code ANTHROPIC_BASE_URL}). */
    public static String sandboxBaseUrl() {
        return "http://" + ALIAS + ":" + PORT;
    }

    /** Creates the ticket's network and (stopped) proxy if they don't exist. Idempotent. */
    public synchronized void ensure(long ticketId) {
        ensureEgressNetwork();
        String net = networkName(ticketId);
        if (!networkExists(net)) {
            try {
                docker.createNetworkCmd().withName(net).withInternal(true).withCheckDuplicate(true)
                        .withLabels(Map.of(DockerSandboxRunner.LABEL_MANAGED, "true",
                                DockerSandboxRunner.LABEL_TICKET, Long.toString(ticketId)))
                        .exec();
            } catch (ConflictException e) {
                // created concurrently
            }
        }
        String name = containerName(ticketId);
        if (inspectState(name) == null) {
            createProxy(ticketId, name, net);
        }
    }

    /** Starts the ticket's proxy (creating it if needed) and waits until it accepts connections. */
    public void start(long ticketId) {
        ensure(ticketId);
        String name = containerName(ticketId);
        try {
            docker.startContainerCmd(name).exec();
        } catch (NotModifiedException e) {
            // already running
        }
        waitUntilListening(name);
    }

    /** Stops the ticket's proxy; afterwards the sandbox can reach nothing. Idempotent. */
    public void stop(long ticketId) {
        try {
            docker.stopContainerCmd(containerName(ticketId)).withTimeout(2).exec();
        } catch (NotFoundException | NotModifiedException e) {
            // gone or already stopped
        }
    }

    public boolean isRunning(long ticketId) {
        Boolean running = inspectState(containerName(ticketId));
        return Boolean.TRUE.equals(running);
    }

    /** Removes the ticket's proxy and network. The sandbox must be removed first (a network in use can't go). */
    public void remove(long ticketId) {
        try {
            docker.removeContainerCmd(containerName(ticketId)).withForce(true).exec();
        } catch (NotFoundException e) {
            // already gone
        }
        try {
            docker.removeNetworkCmd(networkName(ticketId)).exec();
        } catch (NotFoundException e) {
            // already gone
        }
    }

    /** Ticket ids that still have a proxy container or network (for the janitor). */
    public Set<Long> ticketIds() {
        Set<Long> ids = new HashSet<>();
        docker.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(DockerSandboxRunner.LABEL_MANAGED, "true", LABEL_ROLE, "proxy")).exec()
                .forEach(c -> ids.add(Long.parseLong(c.getLabels().get(DockerSandboxRunner.LABEL_TICKET))));
        for (Network n : docker.listNetworksCmd().exec()) {
            Map<String, String> labels = n.getLabels();
            if (labels != null && "true".equals(labels.get(DockerSandboxRunner.LABEL_MANAGED))
                    && labels.containsKey(DockerSandboxRunner.LABEL_TICKET)) {
                ids.add(Long.parseLong(labels.get(DockerSandboxRunner.LABEL_TICKET)));
            }
        }
        return ids;
    }

    /** The nginx configuration: one location that forwards to the upstream and swaps the credential. */
    String nginxConfig() {
        String host = upstream.getHost() + (upstream.getPort() > 0 ? ":" + upstream.getPort() : "");
        String origin = upstream.getScheme() + "://" + host;
        String credentialHeaders = switch (mode) {
            case API_KEY -> "proxy_set_header x-api-key \"" + credential + "\";\n"
                    + "    proxy_set_header Authorization \"\";\n";
            case OAUTH_TOKEN -> "proxy_set_header Authorization \"Bearer " + credential + "\";\n"
                    + "    proxy_set_header x-api-key \"\";\n";
        };
        return """
                server {
                  listen %d;
                  client_max_body_size 64m;
                  location /v1/ {
                    proxy_pass %s;
                    proxy_http_version 1.1;
                    proxy_set_header Host "%s";
                    proxy_set_header Connection "";
                    proxy_set_header X-Forwarded-For "";
                    %s
                    proxy_ssl_server_name on;
                    proxy_ssl_verify on;
                    proxy_ssl_trusted_certificate /etc/ssl/certs/ca-certificates.crt;
                    proxy_buffering off;
                    proxy_read_timeout 900s;
                    proxy_send_timeout 900s;
                  }
                  location / { return 403; }
                }
                """.formatted(PORT, origin, host, credentialHeaders.strip());
    }

    // ---- internals ----

    private void createProxy(long ticketId, String name, String net) {
        HostConfig host = HostConfig.newHostConfig()
                .withNetworkMode(egressNetwork)
                .withCapDrop(Capability.ALL)
                .withCapAdd(Capability.CHOWN, Capability.SETUID, Capability.SETGID) // nginx drops to its own user
                .withSecurityOpts(List.of("no-new-privileges:true"))
                .withMemory(64L * 1024 * 1024)
                .withPidsLimit(64L);
        try {
            createContainer(name, ticketId, host);
        } catch (NotFoundException e) {
            pull();
            createContainer(name, ticketId, host);
        } catch (ConflictException e) {
            return; // created concurrently
        }
        docker.connectToNetworkCmd().withNetworkId(net).withContainerId(name)
                .withContainerNetwork(new ContainerNetwork().withAliases(ALIAS)).exec();
    }

    private void createContainer(String name, long ticketId, HostConfig host) {
        docker.createContainerCmd(image)
                .withName(name)
                .withEnv("FACTORY_NGINX_CONF=" + nginxConfig())
                .withCmd("sh", "-c", "printf '%s' \"$FACTORY_NGINX_CONF\" > /etc/nginx/conf.d/default.conf"
                        + " && exec nginx -g 'daemon off;'")
                .withLabels(Map.of(DockerSandboxRunner.LABEL_MANAGED, "true", LABEL_ROLE, "proxy",
                        DockerSandboxRunner.LABEL_TICKET, Long.toString(ticketId)))
                .withHostConfig(host)
                .exec();
    }

    private void pull() {
        log.info("Pulling model proxy image {}", image);
        try {
            docker.pullImageCmd(image).start().awaitCompletion(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepFailedException("interrupted while pulling " + image, e);
        }
    }

    private void ensureEgressNetwork() {
        if (!networkExists(egressNetwork)) {
            try {
                docker.createNetworkCmd().withName(egressNetwork).withCheckDuplicate(true)
                        .withLabels(Map.of(DockerSandboxRunner.LABEL_MANAGED, "true")).exec();
            } catch (ConflictException e) {
                // created concurrently
            }
        }
    }

    private boolean networkExists(String name) {
        try {
            docker.inspectNetworkCmd().withNetworkId(name).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    /** null if the container doesn't exist, else whether it is running. */
    private Boolean inspectState(String name) {
        try {
            return Boolean.TRUE.equals(docker.inspectContainerCmd(name).exec().getState().getRunning());
        } catch (NotFoundException e) {
            return null;
        }
    }

    private void waitUntilListening(String name) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            var state = docker.inspectContainerCmd(name).exec().getState();
            if (!Boolean.TRUE.equals(state.getRunning())) {
                throw new StepFailedException("model proxy " + name + " exited (code " + state.getExitCodeLong()
                        + "); see `docker logs " + name + "`");
            }
            // nginx writes its pid file once it has bound the port
            var exec = docker.execCreateCmd(name).withCmd("test", "-s", "/var/run/nginx.pid").exec();
            try {
                docker.execStartCmd(exec.getId()).start().awaitCompletion(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepFailedException("interrupted while starting the model proxy", e);
            }
            Long code = docker.inspectExecCmd(exec.getId()).exec().getExitCodeLong();
            if (code != null && code == 0) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StepFailedException("interrupted while starting the model proxy", e);
            }
        }
        throw new StepFailedException("model proxy " + name + " did not start within 20s");
    }
}
