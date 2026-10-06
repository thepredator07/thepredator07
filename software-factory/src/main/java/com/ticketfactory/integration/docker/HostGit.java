package com.ticketfactory.integration.docker;

import com.ticketfactory.integration.BranchPolicy;
import com.ticketfactory.integration.StepFailedException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Git on the factory host. The host is the only place that talks to the remote and holds credentials: it keeps a bare
 * clone per target repo, hands the sandbox a bundle of the base branch, and pushes the ticket branch back from a
 * bundle the sandbox produces. Sandboxes never see the token and need no network.
 */
public class HostGit {

    private final Path mirrorsDir;
    private final String cloneUrlTemplate;
    private final Supplier<String> token;
    private final Duration timeout;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    /** @param token GitHub token for https remotes, or null (local/file remotes in tests) */
    public HostGit(Path workDir, String cloneUrlTemplate, Supplier<String> token, Duration timeout) {
        this.mirrorsDir = workDir.resolve("mirrors");
        this.cloneUrlTemplate = cloneUrlTemplate;
        this.token = token;
        this.timeout = timeout;
    }

    /** Fetches the latest state and writes a bundle of {@code baseBranch} to a temp file (caller deletes it). */
    public Path bundleBase(String repo, String baseBranch) {
        return withRepo(repo, dir -> {
            Path bundle = Files.createTempFile("factory-base-", ".bundle");
            git(dir.getParent(), "-C", dir.toString(), "bundle", "create", bundle.toString(), "refs/heads/" + baseBranch);
            return bundle;
        });
    }

    /** Imports {@code branch} from a bundle the sandbox made and force-pushes it (factory branches only). */
    public void pushBranch(String repo, Path bundle, String branch) {
        BranchPolicy.validateBranch(branch);
        String ref = "refs/heads/" + branch;
        withRepo(repo, dir -> {
            git(dir.getParent(), "-C", dir.toString(), "fetch", "--quiet", bundle.toString(), "+" + ref + ":" + ref);
            git(dir.getParent(), "-C", dir.toString(), "push", "--quiet", "--force", "origin", ref + ":" + ref);
            return null;
        });
    }

    private interface RepoWork<T> {
        T apply(Path dir) throws IOException;
    }

    /** Runs {@code work} on an up-to-date bare clone, one operation per repo at a time. */
    private <T> T withRepo(String repo, RepoWork<T> work) {
        ReentrantLock lock = locks.computeIfAbsent(repo, r -> new ReentrantLock());
        lock.lock();
        try {
            Path dir = mirrorsDir.resolve(repo.replace('/', '_') + ".git");
            if (!Files.exists(dir.resolve("HEAD"))) {
                Files.createDirectories(mirrorsDir);
                git(mirrorsDir, "clone", "--quiet", "--bare", cloneUrlTemplate.replace("{repo}", repo), dir.toString());
            }
            git(mirrorsDir, "-C", dir.toString(), "fetch", "--quiet", "--prune", "origin", "+refs/heads/*:refs/heads/*");
            return work.apply(dir);
        } catch (IOException e) {
            throw new StepFailedException("host git failed for " + repo + ": " + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    private void git(Path cwd, String... args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.put("GIT_TERMINAL_PROMPT", "0");
        String t = token == null ? null : token.get();
        if (t != null && !t.isBlank()) {
            // Passed through the environment, never the command line (visible in `ps`) or a config file.
            String basic = Base64.getEncoder().encodeToString(("x-access-token:" + t).getBytes(StandardCharsets.UTF_8));
            env.put("GIT_CONFIG_COUNT", "1");
            env.put("GIT_CONFIG_KEY_0", "http.extraHeader");
            env.put("GIT_CONFIG_VALUE_0", "Authorization: Basic " + basic);
        }
        Process p = pb.start();
        try {
            if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                p.destroyForcibly();
                throw new IOException("git " + args[0] + " timed out after " + timeout);
            }
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.exitValue() != 0) {
            throw new IOException("git " + String.join(" ", redact(args)) + " exited " + p.exitValue() + ": " + out.strip());
        }
    }

    private static List<String> redact(String[] args) {
        return List.of(args).stream().map(a -> a.contains("@") && a.contains("://") ? a.replaceAll("//[^@]+@", "//***@") : a)
                .toList();
    }
}
