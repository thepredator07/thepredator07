package com.ticketfactory.integration.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.ticketfactory.integration.BranchPolicy;
import com.ticketfactory.integration.SandboxRunner;
import com.ticketfactory.integration.StepFailedException;
import com.ticketfactory.integration.TicketContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One locked-down container per ticket attempt, named {@code factory-<ticketId>}.
 *
 * <ul>
 *   <li><b>No network</b> ({@code --network none}) and <b>no credentials</b>: code goes in and out as git bundles,
 *       streamed through {@code docker exec}; the host does all fetching and pushing ({@link HostGit}). With a
 *       {@link ModelApiProxy}, the sandbox is on its own internal network instead, where the only thing it can reach
 *       is that proxy, and only while the agent runs.</li>
 *   <li>Non-root user 1000, read-only root filesystem, all capabilities dropped, {@code no-new-privileges}, limits on
 *       memory, CPU and processes; an init process reaps orphans.</li>
 *   <li>{@code /workspace} and {@code /tmp} are size-limited tmpfs owned by the sandbox user. A sandbox that stopped
 *       (Docker or host restart) has lost its workspace, so {@link #prepare} replaces it instead of reusing it.</li>
 *   <li>Labelled {@code factory.managed=true} and {@code factory.ticket-id=<id>} so the janitor can find orphans.</li>
 * </ul>
 */
public class DockerSandboxRunner implements SandboxRunner {

    public static final String LABEL_MANAGED = "factory.managed";
    public static final String LABEL_TICKET = "factory.ticket-id";
    public static final String REPO_DIR = "/workspace/repo";
    private static final String SANDBOX_USER = "1000:1000";
    private static final Logger log = LoggerFactory.getLogger(DockerSandboxRunner.class);

    private final DockerClient docker;
    private final HostGit git;
    private final SandboxProperties props;
    private final String baseBranch;
    private final ModelApiProxy proxy; // null: no network at all

    public record ExecResult(int exitCode, String output) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** A sandbox with no network at all (checks only, no agent). */
    public DockerSandboxRunner(DockerClient docker, HostGit git, SandboxProperties props, String baseBranch) {
        this(docker, git, props, baseBranch, null);
    }

    /** With a proxy, each sandbox can reach the model API through it, and nothing else. */
    public DockerSandboxRunner(DockerClient docker, HostGit git, SandboxProperties props, String baseBranch,
                               ModelApiProxy proxy) {
        this.docker = docker;
        this.git = git;
        this.props = props;
        this.baseBranch = baseBranch;
        this.proxy = proxy;
    }

    public ModelApiProxy proxy() {
        return proxy;
    }

    /** The ticket id in a sandbox name ({@code factory-<id>}). */
    public static long ticketIdOf(String sandboxId) {
        if (!sandboxId.matches("factory-\\d+")) {
            throw new IllegalArgumentException("not a sandbox name: " + sandboxId);
        }
        return Long.parseLong(sandboxId.substring("factory-".length()));
    }

    public static String containerName(long ticketId) {
        return "factory-" + ticketId;
    }

    @Override
    public Sandbox prepare(TicketContext ticket) {
        String name = containerName(ticket.ticketId());
        if (proxy != null) {
            proxy.ensure(ticket.ticketId()); // the sandbox's network must exist before the sandbox
        }
        InspectContainerResponse existing = inspect(name);
        if (existing != null && Boolean.TRUE.equals(existing.getState().getRunning()) && repoReady(name)) {
            return sandbox(ticket.ticketId(), name);
        }
        if (existing != null) {
            log.info("Replacing sandbox {} (stopped or half-prepared)", name);
            destroy(name);
        }
        Path bundle = git.bundleBase(ticket.repo(), baseBranch);
        try {
            create(name, ticket);
            copyIn(name, bundle, "/tmp/base.bundle");
            ExecResult r = exec(name, Duration.ofMinutes(5), "sh", "-c", String.join(" && ",
                    "git clone -q -b " + baseBranch + " /tmp/base.bundle " + REPO_DIR,
                    "cd " + REPO_DIR,
                    "git remote remove origin",
                    "git checkout -q -b " + shellSafe(ticket.branchName()),
                    "git config user.name 'Software Factory'",
                    "git config user.email 'factory@users.noreply.github.com'",
                    "rm -f /tmp/base.bundle"));
            if (!r.ok()) {
                throw new StepFailedException("sandbox " + name + ": checkout failed: " + r.output());
            }
            return sandbox(ticket.ticketId(), name);
        } catch (RuntimeException e) {
            destroy(name); // a half-made sandbox is never reused
            throw e instanceof StepFailedException ? e : new StepFailedException("sandbox " + name + ": " + e, e);
        } finally {
            deleteQuietly(bundle);
        }
    }

    @Override
    public void publishBranch(TicketContext ticket, String sandboxId) {
        String branch = ticket.branchName();
        BranchPolicy.validateBranch(branch);
        ExecResult r = exec(sandboxId, Duration.ofMinutes(5), "git", "-C", REPO_DIR, "bundle", "create",
                "/tmp/out.bundle", "refs/heads/" + branch);
        if (!r.ok()) {
            throw new StepFailedException("sandbox " + sandboxId + ": could not bundle " + branch + ": " + r.output());
        }
        Path bundle = copyOut(sandboxId, "/tmp/out.bundle");
        try {
            git.pushBranch(ticket.repo(), bundle, branch);
        } finally {
            deleteQuietly(bundle);
            exec(sandboxId, Duration.ofSeconds(30), "rm", "-f", "/tmp/out.bundle");
        }
    }

    @Override
    public void destroy(String sandboxId) {
        try {
            docker.removeContainerCmd(sandboxId).withForce(true).withRemoveVolumes(true).exec();
        } catch (NotFoundException e) {
            // already gone
        }
        if (proxy != null) {
            proxy.remove(ticketIdOf(sandboxId));
        }
    }

    @Override
    public List<Sandbox> list() {
        List<Container> containers = docker.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of(LABEL_MANAGED, "true")).exec();
        // One entry per ticket that still has anything: its sandbox, or a proxy or network left behind by a crash.
        // Destroying the entry removes all of them.
        java.util.Set<Long> ids = new java.util.TreeSet<>();
        containers.forEach(c -> ids.add(Long.parseLong(c.getLabels().get(LABEL_TICKET))));
        if (proxy != null) {
            ids.addAll(proxy.ticketIds());
        }
        return ids.stream().map(id -> new Sandbox(containerName(id), id, REPO_DIR)).toList();
    }

    /** Runs a command inside the sandbox as the sandbox user. Used by checks (M4) and the agent (M5). */
    /** Writes {@code content} to {@code path} inside the sandbox, as the sandbox user. */
    public void writeFile(String sandboxId, String path, String content) {
        try {
            Path tmp = Files.createTempFile("factory-in-", ".txt");
            try {
                Files.writeString(tmp, content);
                copyIn(sandboxId, tmp, path);
            } finally {
                deleteQuietly(tmp);
            }
        } catch (IOException e) {
            throw new StepFailedException("could not write " + path + " into sandbox: " + e, e);
        }
    }

    public ExecResult exec(String sandboxId, Duration timeout, String... cmd) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int code = run(sandboxId, timeout, null, out, out, null, List.of(), cmd);
        return new ExecResult(code, out.toString(StandardCharsets.UTF_8));
    }

    /**
     * Runs {@code cmd} in {@code workdir} (null: the repo) with extra environment variables, streaming stdout and
     * stderr into {@code out} as they arrive, so the caller decides how much to keep. Returns the exit code.
     */
    public int exec(String sandboxId, Duration timeout, String workdir, List<String> env, java.io.OutputStream out,
                    String... cmd) {
        return run(sandboxId, timeout, null, out, out, workdir == null ? REPO_DIR : workdir, env, cmd);
    }

    /**
     * Runs {@code cmd} with the given stdin and stdout streams (stderr is collected for error messages). This is how
     * files move in and out: Docker's archive API refuses to write into a container with a read-only root filesystem.
     */
    private int run(String sandboxId, Duration timeout, InputStream stdin, java.io.OutputStream stdout,
                    java.io.OutputStream stderr, String workdir, List<String> env, String... cmd) {
        var create = docker.execCreateCmd(sandboxId).withCmd(cmd).withAttachStdout(true).withAttachStderr(true)
                .withAttachStdin(stdin != null);
        if (workdir != null) {
            create = create.withWorkingDir(workdir);
        }
        if (!env.isEmpty()) {
            create = create.withEnv(env);
        }
        String execId = create.exec().getId();
        var start = docker.execStartCmd(execId);
        if (stdin != null) {
            start = start.withStdIn(stdin);
        }
        try (ResultCallback.Adapter<Frame> callback = start.exec(new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                try {
                    (frame.getStreamType() == com.github.dockerjava.api.model.StreamType.STDERR ? stderr : stdout)
                            .write(frame.getPayload());
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        })) {
            if (!callback.awaitCompletion(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new StepFailedException("sandbox " + sandboxId + ": '" + cmd[0] + "' timed out after " + timeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepFailedException("interrupted while running in sandbox", e);
        } catch (IOException e) {
            throw new StepFailedException("sandbox exec failed: " + e, e);
        }
        Long code = docker.inspectExecCmd(execId).exec().getExitCodeLong();
        return code == null ? -1 : code.intValue();
    }

    /** The Docker configuration of a sandbox, for security tests and diagnostics. */
    public InspectContainerResponse inspect(String sandboxId) {
        try {
            return docker.inspectContainerCmd(sandboxId).exec();
        } catch (NotFoundException e) {
            return null;
        }
    }

    // ---- internals ----

    private void create(String name, TicketContext ticket) {
        try {
            createContainer(name, ticket);
        } catch (NotFoundException e) {
            pullImage(); // first sandbox on this host
            createContainer(name, ticket);
        }
        docker.startContainerCmd(name).exec();
    }

    private void pullImage() {
        log.info("Pulling sandbox image {}", props.image());
        try {
            docker.pullImageCmd(props.image()).start().awaitCompletion(15, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepFailedException("interrupted while pulling " + props.image(), e);
        } catch (RuntimeException e) {
            throw new StepFailedException("sandbox image " + props.image() + " is not on this host and could not be"
                    + " pulled (" + e.getMessage() + "). Build it: docker build -t factory-sandbox:latest"
                    + " software-factory/sandbox", e);
        }
    }

    private void createContainer(String name, TicketContext ticket) {
        HostConfig host = HostConfig.newHostConfig()
                .withNetworkMode(proxy == null ? "none" : ModelApiProxy.networkName(ticket.ticketId()))
                .withReadonlyRootfs(true)
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges:true"))
                .withMemory(SandboxProperties.bytes(props.memory()))
                .withMemorySwap(SandboxProperties.bytes(props.memory())) // no swap on top
                .withNanoCPUs((long) (props.cpus() * 1_000_000_000L))
                .withPidsLimit(props.pidsLimit())
                // An init process as PID 1 reaps orphans. Without it, every process killed by a check timeout stays a
                // zombie (sleep, the main process, never reaps) and they add up to the pids limit.
                .withInit(true)
                .withTmpFs(Map.of(
                        "/workspace", "rw,uid=1000,gid=1000,mode=0755,size=" + props.workspaceSize(),
                        "/tmp", "rw,uid=1000,gid=1000,mode=1777,size=" + props.tmpSize()));
        docker.createContainerCmd(props.image())
                .withName(name)
                .withUser(SANDBOX_USER)
                .withEnv("HOME=/tmp", "GIT_TERMINAL_PROMPT=0")
                .withWorkingDir("/workspace")
                .withCmd("sleep", "infinity")
                .withLabels(Map.of(LABEL_MANAGED, "true", LABEL_TICKET, Long.toString(ticket.ticketId()),
                        "factory.repo", ticket.repo(), "factory.branch", ticket.branchName()))
                .withHostConfig(host)
                .exec();
    }

    private boolean repoReady(String name) {
        return exec(name, Duration.ofSeconds(30), "git", "-C", REPO_DIR, "rev-parse", "--verify", "HEAD").ok();
    }

    private Sandbox sandbox(long ticketId, String name) {
        return new Sandbox(name, ticketId, REPO_DIR);
    }

    private void copyIn(String container, Path file, String target) {
        if (!target.matches("/[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("unsafe path: " + target);
        }
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (InputStream in = Files.newInputStream(file)) {
            // head -c stops after exactly N bytes. `cat` would wait for end-of-input, which docker-java's stdin never
            // signals (the exec would hang until its timeout).
            long size = Files.size(file);
            int code = run(container, Duration.ofMinutes(5), in, err, err, null, List.of(), "sh", "-c",
                    "head -c " + size + " > " + target + " && test $(wc -c < " + target + ") -eq " + size);
            if (code != 0) {
                throw new StepFailedException("copy into sandbox failed: " + err.toString(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new StepFailedException("copy into sandbox failed: " + e, e);
        }
    }

    private Path copyOut(String container, String source) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            Path file = Files.createTempFile("factory-out-", ".bundle");
            try (var out = Files.newOutputStream(file)) {
                int code = run(container, Duration.ofMinutes(5), null, out, err, null, List.of(), "cat", source);
                if (code != 0) {
                    throw new StepFailedException("copy out of sandbox failed: " + err.toString(StandardCharsets.UTF_8));
                }
            }
            return file;
        } catch (IOException e) {
            throw new StepFailedException("copy out of sandbox failed: " + e, e);
        }
    }

    /** Branch names are already restricted by BranchPolicy; this is a second line of defense for the shell. */
    private static String shellSafe(String s) {
        if (!s.matches("[A-Za-z0-9._/-]+")) {
            throw new IllegalArgumentException("unsafe branch name: " + s);
        }
        return s;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // temp file
        }
    }
}
