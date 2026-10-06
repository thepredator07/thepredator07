package com.ticketfactory.integration.docker;

import com.github.dockerjava.api.DockerClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.testcontainers.DockerClientFactory;

/**
 * A real Docker sandbox runner wired to a local bare git repository as the "remote" (stands in for GitHub), plus
 * helpers to inspect that remote. Ticket ids get a random base so parallel runs and leftovers never collide.
 */
public final class DockerSandboxFixture implements AutoCloseable {

    public static final String REPO = "acme/app";
    public static final String IMAGE = "buildpack-deps:bookworm-scm";
    /** A token the host would use for an https remote. Tests check it never appears inside a sandbox. */
    public static final String FAKE_TOKEN = "ghp_NEVER_IN_SANDBOX_123";

    public final Path root;
    public final Path origin;
    public final DockerClient docker = DockerClientFactory.instance().client();
    public DockerSandboxRunner runner;
    public final long idBase = 7_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L) * 10;

    public DockerSandboxFixture() throws IOException {
        root = Files.createTempDirectory("factory-sandbox-test");
        origin = root.resolve("remotes").resolve(REPO + ".git");
        Path seed = root.resolve("seed");
        Files.createDirectories(origin.getParent());
        git(root, "init", "-q", "--bare", "-b", "main", origin.toString());
        git(root, "init", "-q", "-b", "main", seed.toString());
        Files.writeString(seed.resolve("README.md"), "# acme/app\n");
        git(seed, "add", ".");
        git(seed, "-c", "user.name=seed", "-c", "user.email=seed@x", "commit", "-q", "-m", "initial");
        git(seed, "push", "-q", origin.toString(), "main");

        runner = runnerWithImage(IMAGE);
    }

    public DockerSandboxRunner runnerWithImage(String image) {
        return runnerWithProxy(image, null);
    }

    /** A runner whose sandboxes reach the model API through {@code proxy} (null: no network). */
    public DockerSandboxRunner runnerWithProxy(String image, ModelApiProxy proxy) {
        SandboxProperties props = new SandboxProperties(image, "1g", 1.0, 256, "256m", "128m", root.resolve("host"),
                "file://" + root.resolve("remotes") + "/{repo}.git", Duration.ofMinutes(2), Duration.ofMinutes(10));
        return new DockerSandboxRunner(docker,
                new HostGit(props.hostWorkDir(), props.cloneUrlTemplate(), () -> FAKE_TOKEN, props.gitTimeout()),
                props, "main", proxy);
    }

    /** Commits {@code files} (path to content) to {@code main} on the remote, as a maintainer pushing would. */
    public void commitToMain(java.util.Map<String, String> files, String message) {
        Path work = root.resolve("work-" + System.nanoTime());
        git(root, "clone", "-q", "-b", "main", origin.toString(), work.toString());
        try {
            for (var f : files.entrySet()) {
                Path file = work.resolve(f.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, f.getValue());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        git(work, "add", ".");
        git(work, "-c", "user.name=seed", "-c", "user.email=seed@x", "commit", "-q", "--allow-empty", "-m", message);
        git(work, "push", "-q", "origin", "main");
    }

    /** Replaces everything on {@code main} with {@code files} (one commit), e.g. to switch between eval fixtures. */
    public void replaceMain(java.util.Map<String, String> files, String message) {
        Path work = root.resolve("work-" + System.nanoTime());
        git(root, "clone", "-q", "-b", "main", origin.toString(), work.toString());
        git(work, "rm", "-rq", "--ignore-unmatch", ".");
        try {
            for (var f : files.entrySet()) {
                Path file = work.resolve(f.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, f.getValue());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        git(work, "add", "-A");
        git(work, "-c", "user.name=seed", "-c", "user.email=seed@x", "commit", "-q", "--allow-empty", "-m", message);
        git(work, "push", "-q", "origin", "main");
    }

    public long id(long n) {
        return idBase + n;
    }

    /** Commit id of {@code branch} on the remote, or null if it doesn't exist. */
    public String remoteHead(String branch) {
        try {
            return git(root, "--git-dir", origin.toString(), "rev-parse", "--verify", "-q", "refs/heads/" + branch).strip();
        } catch (IllegalStateException e) {
            return null;
        }
    }

    public String remoteFile(String branch, String path) {
        return git(root, "--git-dir", origin.toString(), "show", branch + ":" + path);
    }

    @Override
    public void close() throws IOException {
        runner.list().stream().filter(s -> s.ticketId() >= idBase && s.ticketId() < idBase + 10_000)
                .forEach(s -> runner.destroy(s.id()));
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    public static String git(Path cwd, String... args) {
        try {
            List<String> cmd = new java.util.ArrayList<>(List.of("git"));
            cmd.addAll(List.of(args));
            Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + ": " + out);
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
