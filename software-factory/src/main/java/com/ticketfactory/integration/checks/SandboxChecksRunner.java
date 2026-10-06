package com.ticketfactory.integration.checks;

import com.github.dockerjava.api.exception.DockerException;
import com.ticketfactory.integration.ChecksRunner;
import com.ticketfactory.integration.StepFailedException;
import com.ticketfactory.integration.TicketContext;
import com.ticketfactory.integration.docker.DockerSandboxRunner;
import com.ticketfactory.integration.docker.HostGit;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the target repo's check command inside the ticket's sandbox, on whatever the agent left in the working tree.
 *
 * <ul>
 *   <li>The command comes from {@code .factory.yml} <b>on the base branch, read on the host</b>. The agent controls
 *       everything inside the sandbox, including its copy of that file, so it must not be able to change what "the
 *       checks pass" means.</li>
 *   <li>{@code timeout} inside the sandbox stops the command and everything it started; the host waits a little
 *       longer as a backstop. A timeout counts as failed checks, because the usual cause is the agent's change (a hang
 *       or an endless loop) and the agent should see it.</li>
 *   <li>Output is kept to {@link #MAX_OUTPUT_CHARS} (start and end) in fixed memory, however much the build prints.</li>
 *   <li>Same isolation as the rest of the sandbox: no network, so dependencies must be in the image or the repo.</li>
 * </ul>
 */
public class SandboxChecksRunner implements ChecksRunner {

    public static final int MAX_OUTPUT_CHARS = 64 * 1024;
    static final int CONFIG_MAX_BYTES = 64 * 1024;
    private static final int HEAD_BYTES = 8 * 1024;
    private static final int TAIL_BYTES = MAX_OUTPUT_CHARS - HEAD_BYTES - 1024; // room for the markers
    /** Time for {@code timeout -k} to kill a command that ignored SIGTERM, plus Docker overhead. */
    private static final Duration KILL_GRACE = Duration.ofSeconds(10);
    private static final Duration HOST_BACKSTOP = Duration.ofSeconds(60);
    private static final Logger log = LoggerFactory.getLogger(SandboxChecksRunner.class);

    private final DockerSandboxRunner sandbox;
    private final HostGit git;
    private final ChecksProperties props;
    private final String baseBranch;

    public SandboxChecksRunner(DockerSandboxRunner sandbox, HostGit git, ChecksProperties props, String baseBranch) {
        this.sandbox = sandbox;
        this.git = git;
        this.props = props;
        this.baseBranch = baseBranch;
    }

    @Override
    public ChecksResult run(TicketContext ticket, String sandboxId) {
        ChecksConfig config = config(ticket.repo());
        BoundedOutput out = new BoundedOutput(HEAD_BYTES, TAIL_BYTES);
        long seconds = Math.max(1, config.timeout().toSeconds());
        long started = System.nanoTime();
        int code;
        try {
            code = sandbox.exec(sandboxId, config.timeout().plus(KILL_GRACE).plus(HOST_BACKSTOP), null,
                    List.of("CI=true"), out,
                    "timeout", "-k", KILL_GRACE.toSeconds() + "s", seconds + "s", "sh", "-c", config.command());
        } catch (DockerException e) {
            throw new StepFailedException("sandbox " + sandboxId + ": could not run checks: " + e.getMessage(), e);
        }
        Duration took = Duration.ofNanos(System.nanoTime() - started);
        // timeout(1) exits 124 after SIGTERM and 137 after SIGKILL. A command can exit 124 on its own, hence the clock.
        boolean timedOut = (code == 124 || code == 137) && took.compareTo(config.timeout()) >= 0;
        if (code == 125 || code < 0) { // timeout(1) itself failed, or Docker lost the exit code
            throw new StepFailedException("sandbox " + sandboxId + ": could not run the check command (exit " + code
                    + "): " + out);
        }
        String summary = timedOut
                ? "[checks timed out after " + config.timeout() + " and were stopped]"
                : "[checks exited with code " + code + " after " + took.toSeconds() + "s]";
        log.info("Ticket {} checks in {}: {}", ticket.ticketId(), sandboxId, summary);
        String output = "$ " + config.command() + "\n" + out + (out.totalBytes() == 0 ? "" : "\n") + summary;
        return new ChecksResult(code == 0, truncate(output));
    }

    private ChecksConfig config(String repo) {
        Optional<String> file = git.readFile(repo, baseBranch, props.configFile(), CONFIG_MAX_BYTES);
        return ChecksConfig.resolve(file, props);
    }

    /** The command line is repo-controlled and could be long; never exceed the limit. */
    private static String truncate(String s) {
        return s.length() <= MAX_OUTPUT_CHARS ? s : s.substring(s.length() - MAX_OUTPUT_CHARS);
    }
}
